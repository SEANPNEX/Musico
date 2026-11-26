using System;
using System.IO;
using System.Runtime.InteropServices;

namespace MusicoWindows.Core;

/// <summary>
/// Moves local files and folders to the Windows Recycle Bin via SHFileOperationW,
/// or deletes directly on non-Windows hosts used for cross-platform unit tests.
/// </summary>
public static class FileDeletionHelper
{
    private const uint FO_DELETE = 0x0003;
    private const ushort FOF_SILENT = 0x0004;
    private const ushort FOF_NOCONFIRMATION = 0x0010;
    private const ushort FOF_ALLOWUNDO = 0x0040;
    private const ushort FOF_NOERRORUI = 0x0400;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct SHFILEOPSTRUCTW64
    {
        public IntPtr hwnd;
        public uint wFunc;
        public IntPtr pFrom;
        public IntPtr pTo;
        public ushort fFlags;
        [MarshalAs(UnmanagedType.Bool)]
        public bool fAnyOperationsAborted;
        public IntPtr hNameMappings;
        public IntPtr lpszProgressTitle;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode, Pack = 1)]
    private struct SHFILEOPSTRUCTW32
    {
        public IntPtr hwnd;
        public uint wFunc;
        public IntPtr pFrom;
        public IntPtr pTo;
        public ushort fFlags;
        [MarshalAs(UnmanagedType.Bool)]
        public bool fAnyOperationsAborted;
        public IntPtr hNameMappings;
        public IntPtr lpszProgressTitle;
    }

    [DllImport("shell32.dll", EntryPoint = "SHFileOperationW", CharSet = CharSet.Unicode)]
    private static extern int SHFileOperation64(ref SHFILEOPSTRUCTW64 lpFileOp);

    [DllImport("shell32.dll", EntryPoint = "SHFileOperationW", CharSet = CharSet.Unicode)]
    private static extern int SHFileOperation32(ref SHFILEOPSTRUCTW32 lpFileOp);

    /// <summary>
    /// Moves the file or directory at <paramref name="path"/> to the Recycle Bin on Windows
    /// (or deletes it on non-Windows test environments). Throws a clear exception on failure.
    /// </summary>
    public static void MoveToRecycleBin(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            throw new ArgumentException("Target path must not be empty.", nameof(path));
        }

        string fullPath = Path.GetFullPath(path)
            .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        if (fullPath.Length == 2 && fullPath[1] == ':')
        {
            fullPath += Path.DirectorySeparatorChar;
        }

        bool isFile = File.Exists(fullPath);
        bool isDir = !isFile && Directory.Exists(fullPath);
        if (!isFile && !isDir)
        {
            throw new FileNotFoundException($"Could not find '{fullPath}' on disk.", fullPath);
        }

        if (OperatingSystem.IsWindows())
        {
            IntPtr pFrom = IntPtr.Zero;
            try
            {
                // Double-null-terminated string required by SHFileOperationW.
                pFrom = Marshal.StringToHGlobalUni(fullPath + "\0\0");
                ushort flags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI;

                int result;
                bool aborted;
                if (IntPtr.Size == 8)
                {
                    var op = new SHFILEOPSTRUCTW64
                    {
                        hwnd = IntPtr.Zero,
                        wFunc = FO_DELETE,
                        pFrom = pFrom,
                        pTo = IntPtr.Zero,
                        fFlags = flags,
                        fAnyOperationsAborted = false,
                        hNameMappings = IntPtr.Zero,
                        lpszProgressTitle = IntPtr.Zero
                    };
                    result = SHFileOperation64(ref op);
                    aborted = op.fAnyOperationsAborted;
                }
                else
                {
                    var op = new SHFILEOPSTRUCTW32
                    {
                        hwnd = IntPtr.Zero,
                        wFunc = FO_DELETE,
                        pFrom = pFrom,
                        pTo = IntPtr.Zero,
                        fFlags = flags,
                        fAnyOperationsAborted = false,
                        hNameMappings = IntPtr.Zero,
                        lpszProgressTitle = IntPtr.Zero
                    };
                    result = SHFileOperation32(ref op);
                    aborted = op.fAnyOperationsAborted;
                }

                if (aborted)
                {
                    throw new IOException($"Moving '{fullPath}' to the Recycle Bin was cancelled.");
                }

                if (result != 0)
                {
                    throw new IOException(
                        $"Could not move '{fullPath}' to the Recycle Bin (Windows shell error 0x{result:X}).");
                }
            }
            finally
            {
                if (pFrom != IntPtr.Zero)
                {
                    Marshal.FreeHGlobal(pFrom);
                }
            }

            return;
        }

        if (isFile)
        {
            File.Delete(fullPath);
        }
        else
        {
            Directory.Delete(fullPath, recursive: true);
        }
    }
}
