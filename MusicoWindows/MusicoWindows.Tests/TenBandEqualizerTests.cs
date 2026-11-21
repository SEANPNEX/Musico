using MusicoWindows.Audio;
using MusicoWindows.Models;
using NAudio.Wave;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class TenBandEqualizerTests
{
    private sealed class SineWaveProvider : ISampleProvider
    {
        private readonly float _frequency;
        private readonly float _amplitude;
        private readonly bool _leftOnly;
        private long _sampleIndex;

        public SineWaveProvider(int sampleRate, int channels, float frequency, float amplitude = 0.25f, bool leftOnly = false)
        {
            WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(sampleRate, channels);
            _frequency = frequency;
            _amplitude = amplitude;
            _leftOnly = leftOnly;
        }

        public WaveFormat WaveFormat { get; }

        public int Read(float[] buffer, int offset, int count)
        {
            int channels = WaveFormat.Channels;
            int frames = count / channels;
            for (int f = 0; f < frames; f++)
            {
                float t = (float)(_sampleIndex + f) / WaveFormat.SampleRate;
                float val = _amplitude * MathF.Sin(2f * MathF.PI * _frequency * t);
                for (int ch = 0; ch < channels; ch++)
                {
                    buffer[offset + f * channels + ch] = (_leftOnly && ch > 0) ? 0f : val;
                }
            }
            _sampleIndex += frames;
            return frames * channels;
        }
    }

    private static float Rms(float[] samples)
    {
        double sum = 0;
        foreach (float s in samples) sum += (double)s * s;
        return MathF.Sqrt((float)(sum / samples.Length));
    }

    [Fact]
    public void DisabledEqIsBitExactBypass()
    {
        var input = new SineWaveProvider(44100, 2, 1000f);
        var refSource = new SineWaveProvider(44100, 2, 1000f);
        var eq = new TenBandEqualizerSampleProvider(input, new EqSettings
        {
            Enabled = false,
            PreampDb = 6f,
            Gains = [12f, 12f, 12f, 12f, 12f, 12f, 12f, 12f, 12f, 12f]
        });

        var actual = new float[1024];
        var expected = new float[1024];
        int readActual = eq.Read(actual, 0, actual.Length);
        int readExpected = refSource.Read(expected, 0, expected.Length);

        Assert.Equal(readExpected, readActual);
        Assert.Equal(expected, actual);
    }

    [Fact]
    public void PreampScalesSignalByExpectedDb()
    {
        var eq = new TenBandEqualizerSampleProvider(
            new SineWaveProvider(44100, 2, 1000f, amplitude: 0.25f),
            new EqSettings { Enabled = true, PreampDb = 6f });

        var reference = new SineWaveProvider(44100, 2, 1000f, amplitude: 0.25f);
        var actual = new float[2048];
        var dry = new float[2048];
        eq.Read(actual, 0, actual.Length);
        reference.Read(dry, 0, dry.Length);

        float ratio = Rms(actual) / Rms(dry);
        float expectedRatio = MathF.Pow(10f, 6f / 20f);
        Assert.InRange(ratio, expectedRatio - 0.01f, expectedRatio + 0.01f);
    }

    [Fact]
    public void PeakingBandBoostsAndCutsTargetFrequency()
    {
        var dryBuf = new float[4096];
        new SineWaveProvider(44100, 1, 1000f, amplitude: 0.2f).Read(dryBuf, 0, dryBuf.Length);
        float dryRms = Rms(dryBuf);

        var boostGains = new float[10];
        boostGains[5] = 12f; // 1000 Hz band
        var boostEq = new TenBandEqualizerSampleProvider(
            new SineWaveProvider(44100, 1, 1000f, amplitude: 0.2f),
            new EqSettings { Enabled = true, Gains = boostGains });
        var boostBuf = new float[4096];
        boostEq.Read(boostBuf, 0, boostBuf.Length);
        Assert.True(Rms(boostBuf) > dryRms * 2.5f);

        var cutGains = new float[10];
        cutGains[5] = -12f; // 1000 Hz band
        var cutEq = new TenBandEqualizerSampleProvider(
            new SineWaveProvider(44100, 1, 1000f, amplitude: 0.2f),
            new EqSettings { Enabled = true, Gains = cutGains });
        var cutBuf = new float[4096];
        cutEq.Read(cutBuf, 0, cutBuf.Length);
        Assert.True(Rms(cutBuf) < dryRms * 0.45f);
    }

    [Fact]
    public void ChannelsRemainIndependent()
    {
        var gains = new float[10];
        gains[5] = 12f;
        var eq = new TenBandEqualizerSampleProvider(
            new SineWaveProvider(44100, 2, 1000f, leftOnly: true),
            new EqSettings { Enabled = true, Gains = gains });

        var buffer = new float[1024];
        eq.Read(buffer, 0, buffer.Length);

        for (int i = 0; i < buffer.Length; i += 2)
        {
            Assert.True(float.IsFinite(buffer[i]));
            Assert.Equal(0f, buffer[i + 1]);
        }
    }

    [Fact]
    public void LowSampleRateGuardsAboveNyquistBands()
    {
        var eq = new TenBandEqualizerSampleProvider(
            new SineWaveProvider(22050, 2, 500f),
            new EqSettings
            {
                Enabled = true,
                Gains = [0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 12f] // 16kHz > 11.025kHz Nyquist
            });

        var buffer = new float[1024];
        eq.Read(buffer, 0, buffer.Length);

        Assert.All(buffer, s => Assert.True(float.IsFinite(s)));
    }
}
