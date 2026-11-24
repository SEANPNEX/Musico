using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace MusicoWindows.Extensions;

public sealed class QrCodeMatrix(int version, int size, bool[] modules)
{
    public int Version { get; } = version;
    public int Size { get; } = size;
    public bool[] Modules { get; } = modules;

    public bool Get(int row, int col) => Modules[row * Size + col];
}

/// <summary>
/// Zero-dependency ISO/IEC 18004 QR Code encoder supporting Byte mode, Error Correction Level M,
/// and Versions 1–10 (up to 213 UTF-8 bytes). Pure .NET so unit tests compile cross-platform.
/// </summary>
public static class QrCodeEncoder
{
    private sealed record VersionSpecM(
        int Version,
        int TotalDataCodewords,
        int EcCodewordsPerBlock,
        int Group1Blocks,
        int Group1DataCodewords,
        int Group2Blocks,
        int Group2DataCodewords,
        int[] AlignmentCenters);

    private static readonly VersionSpecM[] VersionSpecsM =
    [
        new(1, 16, 10, 1, 16, 0, 0, []),
        new(2, 28, 16, 1, 28, 0, 0, [6, 18]),
        new(3, 44, 26, 1, 44, 0, 0, [6, 22]),
        new(4, 64, 18, 2, 32, 0, 0, [6, 26]),
        new(5, 86, 24, 2, 43, 0, 0, [6, 30]),
        new(6, 108, 16, 4, 27, 0, 0, [6, 34]),
        new(7, 124, 18, 4, 31, 0, 0, [6, 22, 38]),
        new(8, 154, 22, 2, 38, 2, 39, [6, 24, 42]),
        new(9, 182, 22, 3, 36, 2, 37, [6, 26, 46]),
        new(10, 216, 26, 4, 43, 1, 44, [6, 28, 50])
    ];

    private static readonly int[] GfExp = new int[512];
    private static readonly int[] GfLog = new int[256];

    static QrCodeEncoder()
    {
        int v = 1;
        for (int i = 0; i < 255; i++)
        {
            GfExp[i] = v;
            GfExp[i + 255] = v;
            GfLog[v] = i;
            v <<= 1;
            if ((v & 0x100) != 0)
            {
                v ^= 0x11D;
            }
        }
    }

    public static QrCodeMatrix Encode(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            throw new ArgumentException("QR payload must not be empty.", nameof(text));
        }

        byte[] payload = Encoding.UTF8.GetBytes(text);
        VersionSpecM? spec = VersionSpecsM.FirstOrDefault(cand =>
        {
            int countBits = cand.Version >= 10 ? 16 : 8;
            int requiredBits = 4 + countBits + payload.Length * 8;
            return requiredBits <= cand.TotalDataCodewords * 8;
        });

        if (spec is null)
        {
            throw new ArgumentException($"QR payload exceeds 213 UTF-8 bytes ({payload.Length} bytes).", nameof(text));
        }

        byte[] dataCodewords = EncodeDataCodewords(payload, spec);
        byte[] finalCodewords = InterleaveBlocks(dataCodewords, spec);
        int size = 17 + 4 * spec.Version;

        int bestPenalty = int.MaxValue;
        bool[]? bestModules = null;

        for (int mask = 0; mask < 8; mask++)
        {
            var isFunction = new bool[size * size];
            var modules = new bool[size * size];
            DrawFunctionPatterns(spec, size, modules, isFunction);
            PlaceCodewords(finalCodewords, size, modules, isFunction);
            ApplyMask(mask, size, modules, isFunction);
            DrawFormatBits(mask, size, modules, isFunction);
            int penalty = ComputePenaltyScore(size, modules);
            if (penalty < bestPenalty)
            {
                bestPenalty = penalty;
                bestModules = modules;
            }
        }

        return new QrCodeMatrix(
            spec.Version,
            size,
            bestModules ?? throw new InvalidOperationException("Failed to construct QR matrix."));
    }

    private static byte[] EncodeDataCodewords(byte[] payload, VersionSpecM spec)
    {
        int totalBits = spec.TotalDataCodewords * 8;
        var bits = new List<int>(totalBits);

        void AppendBits(int value, int bitCount)
        {
            for (int i = bitCount - 1; i >= 0; i--)
            {
                bits.Add((value >> i) & 1);
            }
        }

        int countBits = spec.Version >= 10 ? 16 : 8;
        AppendBits(0b0100, 4); // Byte mode
        AppendBits(payload.Length, countBits);
        foreach (byte b in payload)
        {
            AppendBits(b, 8);
        }

        int terminatorLen = Math.Min(4, totalBits - bits.Count);
        for (int i = 0; i < terminatorLen; i++) bits.Add(0);
        while (bits.Count % 8 != 0) bits.Add(0);

        var codewords = new byte[spec.TotalDataCodewords];
        int byteCountFromBits = bits.Count / 8;
        for (int i = 0; i < byteCountFromBits; i++)
        {
            int byteVal = 0;
            for (int b = 0; b < 8; b++)
            {
                byteVal = (byteVal << 1) | bits[i * 8 + b];
            }
            codewords[i] = (byte)byteVal;
        }

        int padIdx = 0;
        for (int i = byteCountFromBits; i < spec.TotalDataCodewords; i++)
        {
            codewords[i] = (byte)(padIdx % 2 == 0 ? 0xEC : 0x11);
            padIdx++;
        }

        return codewords;
    }

    private static byte[] InterleaveBlocks(byte[] dataCodewords, VersionSpecM spec)
    {
        int totalBlocks = spec.Group1Blocks + spec.Group2Blocks;
        var dataBlocks = new List<byte[]>(totalBlocks);
        var ecBlocks = new List<byte[]>(totalBlocks);
        int[] divisor = RsGeneratorPoly(spec.EcCodewordsPerBlock);

        int offset = 0;
        for (int i = 0; i < spec.Group1Blocks; i++)
        {
            byte[] block = dataCodewords.AsSpan(offset, spec.Group1DataCodewords).ToArray();
            offset += spec.Group1DataCodewords;
            dataBlocks.Add(block);
            ecBlocks.Add(RsRemainder(block, divisor));
        }
        for (int i = 0; i < spec.Group2Blocks; i++)
        {
            byte[] block = dataCodewords.AsSpan(offset, spec.Group2DataCodewords).ToArray();
            offset += spec.Group2DataCodewords;
            dataBlocks.Add(block);
            ecBlocks.Add(RsRemainder(block, divisor));
        }

        int totalOutputBytes = dataCodewords.Length + totalBlocks * spec.EcCodewordsPerBlock;
        var output = new byte[totalOutputBytes];
        int outPos = 0;

        int maxDataBlockLen = Math.Max(spec.Group1DataCodewords, spec.Group2DataCodewords);
        for (int col = 0; col < maxDataBlockLen; col++)
        {
            foreach (byte[] block in dataBlocks)
            {
                if (col < block.Length)
                {
                    output[outPos++] = block[col];
                }
            }
        }
        for (int col = 0; col < spec.EcCodewordsPerBlock; col++)
        {
            foreach (byte[] ec in ecBlocks)
            {
                output[outPos++] = ec[col];
            }
        }

        return output;
    }

    private static int[] RsGeneratorPoly(int degree)
    {
        int[] poly = [1];
        for (int i = 0; i < degree; i++)
        {
            var next = new int[poly.Length + 1];
            next[0] = poly[0];
            int root = GfExp[i];
            for (int j = 1; j < poly.Length; j++)
            {
                next[j] = poly[j] ^ GfMul(poly[j - 1], root);
            }
            next[poly.Length] = GfMul(poly[^1], root);
            poly = next;
        }
        return poly;
    }

    private static byte[] RsRemainder(byte[] data, int[] divisor)
    {
        int ecLen = divisor.Length - 1;
        var work = new int[data.Length + ecLen];
        for (int i = 0; i < data.Length; i++)
        {
            work[i] = data[i];
        }
        for (int i = 0; i < data.Length; i++)
        {
            int factor = work[i];
            if (factor != 0)
            {
                for (int j = 1; j < divisor.Length; j++)
                {
                    work[i + j] ^= GfMul(divisor[j], factor);
                }
            }
        }
        var ec = new byte[ecLen];
        for (int i = 0; i < ecLen; i++)
        {
            ec[i] = (byte)work[data.Length + i];
        }
        return ec;
    }

    private static int GfMul(int a, int b) => (a == 0 || b == 0) ? 0 : GfExp[GfLog[a] + GfLog[b]];

    private static void DrawFunctionPatterns(VersionSpecM spec, int size, bool[] modules, bool[] isFunction)
    {
        void SetFn(int r, int c, bool dark)
        {
            if ((uint)r < (uint)size && (uint)c < (uint)size)
            {
                int idx = r * size + c;
                modules[idx] = dark;
                isFunction[idx] = true;
            }
        }

        void DrawFinder(int topRow, int leftCol)
        {
            for (int dr = -1; dr <= 7; dr++)
            {
                for (int dc = -1; dc <= 7; dc++)
                {
                    bool isDark = dr is >= 0 and <= 6 && dc is >= 0 and <= 6 &&
                                  Math.Max(Math.Abs(dr - 3), Math.Abs(dc - 3)) != 2;
                    SetFn(topRow + dr, leftCol + dc, isDark);
                }
            }
        }

        DrawFinder(0, 0);
        DrawFinder(0, size - 7);
        DrawFinder(size - 7, 0);

        for (int i = 8; i < size - 8; i++)
        {
            bool dark = i % 2 == 0;
            SetFn(6, i, dark);
            SetFn(i, 6, dark);
        }

        foreach (int r in spec.AlignmentCenters)
        {
            foreach (int c in spec.AlignmentCenters)
            {
                bool overlapsFinder =
                    (r <= 8 && c <= 8) ||
                    (r <= 8 && c >= size - 9) ||
                    (r >= size - 9 && c <= 8);
                if (overlapsFinder) continue;

                for (int dr = -2; dr <= 2; dr++)
                {
                    for (int dc = -2; dc <= 2; dc++)
                    {
                        SetFn(r + dr, c + dc, Math.Max(Math.Abs(dr), Math.Abs(dc)) != 1);
                    }
                }
            }
        }

        for (int i = 0; i <= 8; i++)
        {
            SetFn(8, i, false);
            SetFn(i, 8, false);
        }
        for (int i = size - 8; i < size; i++)
        {
            SetFn(8, i, false);
            if (i >= size - 7)
            {
                SetFn(i, 8, false);
            }
        }
        SetFn(6, 8, true);
        SetFn(8, 6, true);
        SetFn(size - 8, 8, true);

        if (spec.Version >= 7)
        {
            int rem = spec.Version << 12;
            for (int bit = 17; bit >= 12; bit--)
            {
                if (((rem >> bit) & 1) != 0)
                {
                    rem ^= 0x1F25 << (bit - 12);
                }
            }
            int bits = (spec.Version << 12) | rem;
            for (int k = 0; k < 18; k++)
            {
                bool dark = ((bits >> k) & 1) != 0;
                int a = k / 3;
                int b = size - 11 + (k % 3);
                SetFn(a, b, dark);
                SetFn(b, a, dark);
            }
        }
    }

    private static void PlaceCodewords(byte[] codewords, int size, bool[] modules, bool[] isFunction)
    {
        int totalBits = codewords.Length * 8;
        int bitIdx = 0;
        bool upward = true;
        int right = size - 1;

        while (right >= 1)
        {
            if (right == 6) right = 5;
            for (int step = 0; step < size; step++)
            {
                int row = upward ? size - 1 - step : step;
                for (int dx = 0; dx <= 1; dx++)
                {
                    int col = right - dx;
                    int cell = row * size + col;
                    if (!isFunction[cell])
                    {
                        bool dark = false;
                        if (bitIdx < totalBits)
                        {
                            byte byteVal = codewords[bitIdx >> 3];
                            dark = ((byteVal >> (7 - (bitIdx & 7))) & 1) != 0;
                        }
                        modules[cell] = dark;
                        bitIdx++;
                    }
                }
            }
            upward = !upward;
            right -= 2;
        }
    }

    private static void ApplyMask(int mask, int size, bool[] modules, bool[] isFunction)
    {
        for (int r = 0; r < size; r++)
        {
            for (int c = 0; c < size; c++)
            {
                int idx = r * size + c;
                if (isFunction[idx]) continue;
                bool invert = mask switch
                {
                    0 => (r + c) % 2 == 0,
                    1 => r % 2 == 0,
                    2 => c % 3 == 0,
                    3 => (r + c) % 3 == 0,
                    4 => ((r / 2) + (c / 3)) % 2 == 0,
                    5 => (r * c) % 2 + (r * c) % 3 == 0,
                    6 => (((r * c) % 2) + ((r * c) % 3)) % 2 == 0,
                    7 => (((r + c) % 2) + ((r * c) % 3)) % 2 == 0,
                    _ => false
                };
                if (invert)
                {
                    modules[idx] = !modules[idx];
                }
            }
        }
    }

    private static void DrawFormatBits(int mask, int size, bool[] modules, bool[] isFunction)
    {
        int data = mask & 0x07; // EC Level M = 00
        int rem = data << 10;
        for (int bit = 14; bit >= 10; bit--)
        {
            if (((rem >> bit) & 1) != 0)
            {
                rem ^= 0x537 << (bit - 10);
            }
        }
        int bits = ((data << 10) | rem) ^ 0x5412;

        void SetBit(int r, int c, int bitIndex)
        {
            int idx = r * size + c;
            modules[idx] = ((bits >> bitIndex) & 1) != 0;
            isFunction[idx] = true;
        }

        for (int i = 0; i <= 5; i++) SetBit(8, i, 14 - i);
        SetBit(8, 7, 8);
        SetBit(8, 8, 7);
        SetBit(7, 8, 6);
        for (int i = 5; i >= 0; i--) SetBit(i, 8, i);

        for (int i = 0; i <= 6; i++) SetBit(size - 1 - i, 8, 14 - i);
        for (int i = 0; i <= 7; i++) SetBit(8, size - 8 + i, 7 - i);
    }

    private static int ComputePenaltyScore(int size, bool[] modules)
    {
        int score = 0;

        for (int r = 0; r < size; r++)
        {
            bool runColor = modules[r * size];
            int runLen = 1;
            for (int c = 1; c < size; c++)
            {
                bool dark = modules[r * size + c];
                if (dark == runColor)
                {
                    runLen++;
                    if (runLen == 5) score += 3;
                    else if (runLen > 5) score += 1;
                }
                else
                {
                    runColor = dark;
                    runLen = 1;
                }
            }
            for (int c = 0; c <= size - 11; c++)
            {
                if (MatchesFinderLikePattern(size, modules, r, c, horizontal: true))
                {
                    score += 40;
                }
            }
        }

        for (int c = 0; c < size; c++)
        {
            bool runColor = modules[c];
            int runLen = 1;
            for (int r = 1; r < size; r++)
            {
                bool dark = modules[r * size + c];
                if (dark == runColor)
                {
                    runLen++;
                    if (runLen == 5) score += 3;
                    else if (runLen > 5) score += 1;
                }
                else
                {
                    runColor = dark;
                    runLen = 1;
                }
            }
            for (int r = 0; r <= size - 11; r++)
            {
                if (MatchesFinderLikePattern(size, modules, r, c, horizontal: false))
                {
                    score += 40;
                }
            }
        }

        for (int r = 0; r < size - 1; r++)
        {
            for (int c = 0; c < size - 1; c++)
            {
                bool color = modules[r * size + c];
                if (modules[r * size + c + 1] == color &&
                    modules[(r + 1) * size + c] == color &&
                    modules[(r + 1) * size + c + 1] == color)
                {
                    score += 3;
                }
            }
        }

        int darkCount = 0;
        foreach (bool m in modules)
        {
            if (m) darkCount++;
        }
        int total = size * size;
        double percentDiff = Math.Abs(darkCount * 100.0 / total - 50.0);
        int fivePercentSteps = (int)Math.Floor(percentDiff / 5.0);
        score += fivePercentSteps * 10;

        return score;
    }

    private static bool MatchesFinderLikePattern(int size, bool[] modules, int startR, int startC, bool horizontal)
    {
        bool Bit(int offset) => horizontal
            ? modules[startR * size + startC + offset]
            : modules[(startR + offset) * size + startC];

        if (Bit(0) && !Bit(1) && Bit(2) && Bit(3) && Bit(4) && !Bit(5) &&
            Bit(6) && !Bit(7) && !Bit(8) && !Bit(9) && !Bit(10))
        {
            return true;
        }

        return !Bit(0) && !Bit(1) && !Bit(2) && !Bit(3) && Bit(4) && !Bit(5) &&
               Bit(6) && Bit(7) && Bit(8) && !Bit(9) && Bit(10);
    }
}
