using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Text.Json;
using Jint;
using Jint.Native;
using Jint.Native.Object;
using Jint.Runtime;

namespace MusicoWindows.Extensions;

public sealed record LoadedExtensionPackage(
    ExtensionManifest Manifest,
    string EntryScriptSource,
    byte[]? IconBytes,
    ExtensionPackageFormat Format,
    IReadOnlyDictionary<string, byte[]>? AdditionalFiles = null);

public static class ExtensionPackageLoader
{
    public const int MaxArchiveEntries = 256;
    public const long MaxUncompressedBytes = 16L * 1024 * 1024;
    private const long ValidationMemoryLimitBytes = 32_000_000;

    public static LoadedExtensionPackage LoadFromFile(string filePath)
    {
        if (string.IsNullOrWhiteSpace(filePath) || !File.Exists(filePath))
        {
            throw new FileNotFoundException("Extension file was not found.", filePath);
        }

        byte[] bytes = ReadBoundedFileBytes(filePath);
        return LoadFromBytes(bytes, filePath);
    }

    public static LoadedExtensionPackage LoadFromBytes(byte[] bytes, string? fileNameHint = null)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        if (bytes.Length == 0)
        {
            throw new InvalidDataException("Extension package file is empty.");
        }
        if (bytes.Length > MaxUncompressedBytes)
        {
            throw new InvalidDataException($"Extension package exceeds the maximum size of {MaxUncompressedBytes / (1024 * 1024)} MB.");
        }

        bool looksLikeZip = (bytes.Length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B && bytes[2] == 0x03 && bytes[3] == 0x04) ||
                            (!string.IsNullOrWhiteSpace(fileNameHint) &&
                             fileNameHint.EndsWith(".musicoext", StringComparison.OrdinalIgnoreCase));

        if (looksLikeZip)
        {
            using var ms = new MemoryStream(bytes, writable: false);
            return LoadZipBundle(ms);
        }

        string source = Encoding.UTF8.GetString(bytes);
        return LoadSingleFileScript(source);
    }

    public static LoadedExtensionPackage LoadZipBundle(Stream zipStream)
    {
        ArgumentNullException.ThrowIfNull(zipStream);

        using var archive = new ZipArchive(zipStream, ZipArchiveMode.Read, leaveOpen: true);
        if (archive.Entries.Count > MaxArchiveEntries)
        {
            throw new InvalidDataException($"Extension archive contains {archive.Entries.Count} entries, exceeding limit of {MaxArchiveEntries}.");
        }

        long totalDeclaredSize = 0;
        long totalReadBytes = 0;
        var entriesByPath = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);

        foreach (ZipArchiveEntry entry in archive.Entries)
        {
            string normalizedPath = ValidateAndNormalizeEntryPath(entry.FullName, out bool isDirectory);
            if (isDirectory) continue;

            if (entry.Length < 0)
            {
                throw new InvalidDataException($"Invalid entry length in archive for '{entry.FullName}'.");
            }

            totalDeclaredSize += entry.Length;
            if (totalDeclaredSize > MaxUncompressedBytes)
            {
                throw new InvalidDataException($"Extension archive exceeds the uncompressed limit of {MaxUncompressedBytes / (1024 * 1024)} MB.");
            }

            using Stream entryStream = entry.Open();
            byte[] data = ReadStreamWithRemainingBudget(entryStream, MaxUncompressedBytes - totalReadBytes, entry.FullName);
            totalReadBytes += data.Length;

            entriesByPath[normalizedPath] = data;
        }

        if (!entriesByPath.TryGetValue("manifest.json", out byte[]? manifestBytes))
        {
            throw new InvalidDataException("Extension archive is missing manifest.json.");
        }

        ExtensionManifest manifest;
        try
        {
            manifest = JsonSerializer.Deserialize<ExtensionManifest>(manifestBytes, ExtensionJson.Options)
                ?? throw new InvalidDataException("Extension manifest.json could not be parsed.");
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException($"Invalid extension manifest.json: {ex.Message}", ex);
        }

        ExtensionManifestValidator.Validate(manifest);

        string entryRelPath = manifest.EffectiveEntry.Replace('\\', '/').TrimStart('/');
        if (!entriesByPath.TryGetValue(entryRelPath, out byte[]? scriptBytes))
        {
            throw new InvalidDataException($"Extension archive is missing entry script '{manifest.EffectiveEntry}'.");
        }

        string scriptSource = Encoding.UTF8.GetString(scriptBytes);
        ValidateScriptExports(manifest, scriptSource);

        entriesByPath.TryGetValue("icon.png", out byte[]? iconBytes);

        var additional = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
        foreach (var kvp in entriesByPath)
        {
            if (string.Equals(kvp.Key, "manifest.json", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(kvp.Key, entryRelPath, StringComparison.OrdinalIgnoreCase) ||
                string.Equals(kvp.Key, "icon.png", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }
            additional[kvp.Key] = kvp.Value;
        }

        return new LoadedExtensionPackage(
            Manifest: manifest,
            EntryScriptSource: scriptSource,
            IconBytes: iconBytes,
            Format: ExtensionPackageFormat.Bundle,
            AdditionalFiles: additional.Count > 0 ? additional : null);
    }

    public static LoadedExtensionPackage LoadSingleFileScript(string scriptSource)
    {
        if (string.IsNullOrWhiteSpace(scriptSource))
        {
            throw new InvalidDataException("Extension script file is empty.");
        }
        if (Encoding.UTF8.GetByteCount(scriptSource) > MaxUncompressedBytes)
        {
            throw new InvalidDataException($"Extension script exceeds the maximum size of {MaxUncompressedBytes / (1024 * 1024)} MB.");
        }

        ExtensionManifest manifest;
        try
        {
            var engine = CreateValidationEngine();
            ObjectInstance exports = EvaluateCommonJsModule(engine, scriptSource);

            JsValue manifestVal = exports.Get("manifest");
            if (manifestVal.IsNull() || manifestVal.IsUndefined() || !manifestVal.IsObject())
            {
                throw new InvalidDataException("Single-file extension must export a 'manifest' object on module.exports.");
            }

            var serializer = new Jint.Native.Json.JsonSerializer(engine);
            JsValue jsonVal = serializer.Serialize(manifestVal);
            if (!jsonVal.IsString())
            {
                throw new InvalidDataException("Extension manifest could not be serialized to JSON.");
            }

            manifest = JsonSerializer.Deserialize<ExtensionManifest>(jsonVal.AsString(), ExtensionJson.Options)
                ?? throw new InvalidDataException("Extension manifest could not be parsed.");

            if (string.IsNullOrWhiteSpace(manifest.Entry))
            {
                manifest.Entry = "index.js";
            }

            ExtensionManifestValidator.Validate(manifest);
            AssertRequiredHooks(exports, manifest);
        }
        catch (InvalidDataException)
        {
            throw;
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException($"Invalid extension manifest: {ex.Message}", ex);
        }
        catch (Exception ex) when (ex is JavaScriptException or JintException or TimeoutException or MemoryLimitExceededException)
        {
            throw new InvalidDataException($"Failed to evaluate extension script: {ex.Message}", ex);
        }

        return new LoadedExtensionPackage(
            Manifest: manifest,
            EntryScriptSource: scriptSource,
            IconBytes: null,
            Format: ExtensionPackageFormat.SingleFile);
    }

    public static void InstallToDirectory(LoadedExtensionPackage package, string targetDirectory)
    {
        ArgumentNullException.ThrowIfNull(package);
        if (string.IsNullOrWhiteSpace(targetDirectory))
        {
            throw new ArgumentException("Target directory is required.", nameof(targetDirectory));
        }

        string? parentDir = Path.GetDirectoryName(targetDirectory);
        if (!string.IsNullOrEmpty(parentDir))
        {
            Directory.CreateDirectory(parentDir);
        }

        string stagingDir = $"{targetDirectory}.staging.{Guid.NewGuid():N}";
        try
        {
            Directory.CreateDirectory(stagingDir);

            var indentedOptions = new JsonSerializerOptions(ExtensionJson.Options) { WriteIndented = true };
            byte[] manifestJson = JsonSerializer.SerializeToUtf8Bytes(package.Manifest, indentedOptions);
            File.WriteAllBytes(Path.Combine(stagingDir, "manifest.json"), manifestJson);

            string entryRel = package.Manifest.EffectiveEntry.Replace('\\', '/').TrimStart('/');
            string entryDest = Path.Combine(stagingDir, entryRel.Replace('/', Path.DirectorySeparatorChar));
            string? entryDir = Path.GetDirectoryName(entryDest);
            if (!string.IsNullOrEmpty(entryDir))
            {
                Directory.CreateDirectory(entryDir);
            }
            File.WriteAllText(entryDest, package.EntryScriptSource, Encoding.UTF8);

            if (package.IconBytes is { Length: > 0 })
            {
                File.WriteAllBytes(Path.Combine(stagingDir, "icon.png"), package.IconBytes);
            }

            if (package.AdditionalFiles is not null)
            {
                foreach (var kvp in package.AdditionalFiles)
                {
                    string safeRel = ValidateAndNormalizeEntryPath(kvp.Key, out bool isDir);
                    if (isDir) continue;
                    string dest = Path.Combine(stagingDir, safeRel.Replace('/', Path.DirectorySeparatorChar));
                    string? destParent = Path.GetDirectoryName(dest);
                    if (!string.IsNullOrEmpty(destParent))
                    {
                        Directory.CreateDirectory(destParent);
                    }
                    File.WriteAllBytes(dest, kvp.Value);
                }
            }

            if (Directory.Exists(targetDirectory))
            {
                // Preserve existing kv.json across extension updates if present.
                string existingKv = Path.Combine(targetDirectory, "kv.json");
                if (File.Exists(existingKv))
                {
                    File.Copy(existingKv, Path.Combine(stagingDir, "kv.json"), overwrite: true);
                }
                Directory.Delete(targetDirectory, recursive: true);
            }

            Directory.Move(stagingDir, targetDirectory);
        }
        catch
        {
            try { if (Directory.Exists(stagingDir)) Directory.Delete(stagingDir, recursive: true); } catch { /* best effort */ }
            throw;
        }
    }

    private static void ValidateScriptExports(ExtensionManifest manifest, string scriptSource)
    {
        try
        {
            var engine = CreateValidationEngine();
            ObjectInstance exports = EvaluateCommonJsModule(engine, scriptSource);
            AssertRequiredHooks(exports, manifest);
        }
        catch (InvalidDataException)
        {
            throw;
        }
        catch (Exception ex) when (ex is JavaScriptException or JintException or TimeoutException or MemoryLimitExceededException)
        {
            throw new InvalidDataException($"Failed to validate extension script: {ex.Message}", ex);
        }
    }

    private static void AssertRequiredHooks(ObjectInstance exports, ExtensionManifest manifest)
    {
        bool hasCollectionCap = manifest.HasCapability(ExtensionCapability.Playlist) ||
                                manifest.HasCapability(ExtensionCapability.Tree);

        if (hasCollectionCap && !IsFunction(exports.Get("canHandle")))
        {
            throw new InvalidDataException("Extension must export a 'canHandle' function.");
        }

        if (hasCollectionCap && !IsFunction(exports.Get("fetchCollection")))
        {
            throw new InvalidDataException("Extension must export a 'fetchCollection' function.");
        }

        if (manifest.HasCapability(ExtensionCapability.Tree) && !IsFunction(exports.Get("fetchFolder")))
        {
            throw new InvalidDataException("Extension declares 'tree' capability and must export a 'fetchFolder' function.");
        }

        if (manifest.HasCapability(ExtensionCapability.Stream) && !IsFunction(exports.Get("resolveStream")))
        {
            throw new InvalidDataException("Extension declares 'stream' capability and must export a 'resolveStream' function.");
        }

        if (manifest.HasCapability(ExtensionCapability.Lyrics) && !IsFunction(exports.Get("searchLyrics")))
        {
            throw new InvalidDataException("Extension declares 'lyrics' capability and must export a 'searchLyrics' function.");
        }

        if (manifest.HasCapability(ExtensionCapability.Search) && !IsFunction(exports.Get("search")))
        {
            throw new InvalidDataException("Extension declares 'search' capability and must export a 'search' function.");
        }

        if (manifest.HasCapability(ExtensionCapability.QrLogin))
        {
            if (!IsFunction(exports.Get("startQrLogin")))
            {
                throw new InvalidDataException("Extension declares 'qr_login' capability and must export a 'startQrLogin' function.");
            }
            if (!IsFunction(exports.Get("pollQrLogin")))
            {
                throw new InvalidDataException("Extension declares 'qr_login' capability and must export a 'pollQrLogin' function.");
            }
        }
    }

    private static bool IsFunction(JsValue val) => val is Jint.Native.Function.Function;

    private static Engine CreateValidationEngine() =>
        new(opts =>
        {
            opts.LimitMemory(ValidationMemoryLimitBytes);
            opts.TimeoutInterval(TimeSpan.FromSeconds(5));
        });

    private static ObjectInstance EvaluateCommonJsModule(Engine engine, string scriptSource)
    {
        string wrapped = $"(function(){{\nvar module = {{ exports: {{}} }};\n(function(exports, module) {{\n{scriptSource}\n}})(module.exports, module);\nreturn module.exports;\n}})()";
        JsValue exportsVal = engine.Evaluate(wrapped);
        if (!exportsVal.IsObject())
        {
            throw new InvalidDataException("Extension script did not export an object on module.exports.");
        }
        return exportsVal.AsObject();
    }

    internal static string ValidateAndNormalizeEntryPath(string rawPath, out bool isDirectory)
    {
        if (string.IsNullOrWhiteSpace(rawPath))
        {
            throw new InvalidDataException("Archive contains an entry with an empty path.");
        }

        if (rawPath.StartsWith('/') || rawPath.StartsWith('\\') || rawPath.Contains(':', StringComparison.Ordinal))
        {
            throw new InvalidDataException($"Archive entry '{rawPath}' uses an unsafe absolute path.");
        }

        string normalized = rawPath.Replace('\\', '/');
        isDirectory = normalized.EndsWith('/');
        string trimmed = normalized.TrimEnd('/');
        if (trimmed.Length == 0)
        {
            throw new InvalidDataException($"Archive entry '{rawPath}' is invalid.");
        }

        string[] segments = trimmed.Split('/');
        foreach (string seg in segments)
        {
            if (seg.Length == 0 || seg is "." or "..")
            {
                throw new InvalidDataException($"Archive entry '{rawPath}' contains unsafe path traversal.");
            }
        }

        return trimmed;
    }

    private static byte[] ReadBoundedFileBytes(string path)
    {
        var info = new FileInfo(path);
        if (info.Length > MaxUncompressedBytes)
        {
            throw new InvalidDataException($"Extension file exceeds the maximum size of {MaxUncompressedBytes / (1024 * 1024)} MB.");
        }
        return File.ReadAllBytes(path);
    }

    private static byte[] ReadStreamWithRemainingBudget(Stream stream, long remainingBudget, string entryName)
    {
        using var ms = new MemoryStream();
        byte[] buffer = new byte[16384];
        long readTotal = 0;
        while (true)
        {
            int read = stream.Read(buffer, 0, buffer.Length);
            if (read <= 0) break;
            readTotal += read;
            if (readTotal > remainingBudget)
            {
                throw new InvalidDataException(
                    $"Extension archive exceeds uncompressed size limit of {MaxUncompressedBytes / (1024 * 1024)} MB while reading '{entryName}'.");
            }
            ms.Write(buffer, 0, read);
        }
        return ms.ToArray();
    }
}
