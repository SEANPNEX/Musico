using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Text;
using MusicoWindows.Extensions;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class ExtensionMultiImportTests : IDisposable
{
    private readonly List<string> _tempDirs = new();

    public void Dispose()
    {
        foreach (string d in _tempDirs)
        {
            try { if (Directory.Exists(d)) Directory.Delete(d, recursive: true); } catch { }
        }
    }

    private string NewTempDir()
    {
        string d = TestHelpers.MakeTempDir();
        _tempDirs.Add(d);
        return d;
    }

    private sealed class StubHttpHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return responder(request, cancellationToken);
        }
    }

    private static string MakeScript(string id, string name, string host = "example.com") => $$"""
        'use strict';
        var manifest = {
          apiVersion: 1,
          id: '{{id}}',
          name: '{{name}}',
          version: '1.0.0',
          capabilities: ['search'],
          permissions: { hosts: ['{{host}}'] },
          updateUrl: 'https://github.com/SEANPNEX/musico_plugins/releases/latest/download/{{id}}.musicoext'
        };
        function search(ctx, req) {
          return { items: [], nextCursor: null };
        }
        module.exports = { manifest: manifest, search: search };
        """;

    private static byte[] MakeSingleExtArchiveBytes(string id, string name, string host = "example.com")
    {
        string script = MakeScript(id, name, host);
        string manifestJson = $$"""
            {
              "apiVersion": 1,
              "id": "{{id}}",
              "name": "{{name}}",
              "version": "1.0.0",
              "entry": "index.js",
              "capabilities": ["search"],
              "permissions": { "hosts": ["{{host}}"] },
              "updateUrl": "https://github.com/SEANPNEX/musico_plugins/releases/latest/download/{{id}}.musicoext"
            }
            """;

        using var ms = new MemoryStream();
        using (var archive = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
        {
            AddEntry(archive, "manifest.json", Encoding.UTF8.GetBytes(manifestJson));
            AddEntry(archive, "index.js", Encoding.UTF8.GetBytes(script));
        }
        return ms.ToArray();
    }

    private static byte[] MakeZip(params (string Path, byte[] Bytes)[] entries)
    {
        using var ms = new MemoryStream();
        using (var archive = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var (path, bytes) in entries)
            {
                AddEntry(archive, path, bytes);
            }
        }
        return ms.ToArray();
    }

    private static void AddEntry(ZipArchive archive, string entryPath, byte[] bytes)
    {
        ZipArchiveEntry entry = archive.CreateEntry(entryPath);
        using Stream s = entry.Open();
        s.Write(bytes, 0, bytes.Length);
    }

    [Fact]
    public void LoadPackagesFromBytes_ParsesNestedMusicoextPack()
    {
        byte[] pack = MakeZip(
            ("youtube-search.musicoext", MakeSingleExtArchiveBytes("com.example.yt", "YouTube Search", "youtube.com")),
            ("netease-cloud.musicoext", MakeSingleExtArchiveBytes("com.example.ne", "NetEase Cloud", "music.163.com")),
            ("README.txt", Encoding.UTF8.GetBytes("bundle notes")));

        IReadOnlyList<LoadedExtensionPackage> loaded = ExtensionPackageLoader.LoadPackagesFromBytes(pack, "musico-plugins-all.zip");

        Assert.Equal(2, loaded.Count);
        Assert.Equal("com.example.ne", loaded[0].Manifest.Id);
        Assert.Equal("com.example.yt", loaded[1].Manifest.Id);

        // Single-package loader still rejects multi-plugin archives cleanly.
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromBytes(pack, "musico-plugins-all.zip"));
    }

    [Fact]
    public void LoadPackagesFromBytes_ParsesSubdirectoryBundlesAndIgnoresMacOsJunk()
    {
        string manifestA = """
            {"apiVersion":1,"id":"com.example.sub1","name":"Sub One","version":"1.0.0","entry":"index.js","capabilities":["search"],"permissions":{"hosts":["a.example.com"]}}
            """;
        string manifestB = """
            {"apiVersion":1,"id":"com.example.sub2","name":"Sub Two","version":"1.0.0","entry":"index.js","capabilities":["search"],"permissions":{"hosts":["b.example.com"]}}
            """;

        byte[] pack = MakeZip(
            ("__MACOSX/._manifest.json", Encoding.UTF8.GetBytes("junk")),
            ("sub-one/.DS_Store", Encoding.UTF8.GetBytes("junk")),
            ("sub-one/._index.js", Encoding.UTF8.GetBytes("junk")),
            ("sub-one/manifest.json", Encoding.UTF8.GetBytes(manifestA)),
            ("sub-one/index.js", Encoding.UTF8.GetBytes(MakeScript("com.example.sub1", "Sub One", "a.example.com"))),
            ("sub-two/manifest.json", Encoding.UTF8.GetBytes(manifestB)),
            ("sub-two/index.js", Encoding.UTF8.GetBytes(MakeScript("com.example.sub2", "Sub Two", "b.example.com"))));

        IReadOnlyList<LoadedExtensionPackage> loaded = ExtensionPackageLoader.LoadPackagesFromBytes(pack, "plugins.zip");
        Assert.Equal(2, loaded.Count);
        Assert.Contains(loaded, p => p.Manifest.Id == "com.example.sub1");
        Assert.Contains(loaded, p => p.Manifest.Id == "com.example.sub2");
    }

    [Fact]
    public void LoadPackagesFromBytes_ParsesNestedSingleFileScripts()
    {
        byte[] pack = MakeZip(
            ("alpha.musico.js", Encoding.UTF8.GetBytes(MakeScript("com.example.alpha", "Alpha"))),
            ("beta.musico.js", Encoding.UTF8.GetBytes(MakeScript("com.example.beta", "Beta"))));

        IReadOnlyList<LoadedExtensionPackage> loaded = ExtensionPackageLoader.LoadPackagesFromBytes(pack, "scripts.zip");
        Assert.Equal(2, loaded.Count);
        Assert.Equal("com.example.alpha", loaded[0].Manifest.Id);
        Assert.Equal("com.example.beta", loaded[1].Manifest.Id);
    }

    [Fact]
    public void LoadPackagesFromBytes_RejectsDuplicatesAndEmptyPacks()
    {
        byte[] duplicatePack = MakeZip(
            ("one.musicoext", MakeSingleExtArchiveBytes("com.example.dup", "Dup 1")),
            ("two.musico.js", Encoding.UTF8.GetBytes(MakeScript("com.example.dup", "Dup 2"))));

        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadPackagesFromBytes(duplicatePack, "dup.zip"));

        byte[] emptyPack = MakeZip(("notes.txt", Encoding.UTF8.GetBytes("no extensions here")));
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadPackagesFromBytes(emptyPack, "empty.zip"));
    }

    [Fact]
    public async Task ExtensionStore_ImportsMultiPluginZipFromFileAndUrlAndApprovesBatch()
    {
        string stateDir = NewTempDir();
        byte[] pack = MakeZip(
            ("first.musicoext", MakeSingleExtArchiveBytes("com.example.first", "First", "first.example.com")),
            ("second.musicoext", MakeSingleExtArchiveBytes("com.example.second", "Second", "second.example.com")));

        string zipPath = Path.Combine(NewTempDir(), "musico-plugins-all.zip");
        await File.WriteAllBytesAsync(zipPath, pack);

        var handler = new StubHttpHandler((_, _) =>
        {
            var resp = new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent(pack)
            };
            return Task.FromResult(resp);
        });

        var store = new ExtensionStore(stateDirectory: stateDir, httpHandler: handler);

        IReadOnlyList<InstalledExtension> importedFromFile = await store.ImportPackagesFromFileAsync(zipPath);
        Assert.Equal(2, importedFromFile.Count);
        Assert.All(importedFromFile, ext => Assert.True(ext.PendingPermissionReview));
        Assert.All(importedFromFile, ext => Assert.False(ext.IsEnabled));

        store.ApproveAndEnableBatch(importedFromFile.Select(e => e.Id));
        Assert.All(store.Extensions, ext => Assert.True(ext.IsEnabled));

        // Multi-plugin URL import must NOT overwrite individual auto-update URLs with the pack URL.
        string packUrl = "https://github.com/SEANPNEX/musico_plugins/releases/latest/download/musico-plugins-all.zip";
        IReadOnlyList<InstalledExtension> importedFromUrl = await store.ImportPackagesFromUrlAsync(packUrl);
        Assert.Equal(2, importedFromUrl.Count);
        Assert.All(importedFromUrl, ext => Assert.Null(ext.State.SourceUrl));
        Assert.Equal(
            "https://github.com/SEANPNEX/musico_plugins/releases/latest/download/com.example.first.musicoext",
            ExtensionStore.ResolveUpdateUrl(importedFromUrl.First(e => e.Id == "com.example.first")));
    }
}
