using MusicoWindows.Models;
using NAudio.Dsp;
using NAudio.Wave;

namespace MusicoWindows.Audio;

/// <summary>
/// 10-band peaking EQ + preamp gain sample provider using NAudio BiQuadFilter.
/// Multi-channel safe (independent filter chains per channel) and thread-safe for live EQ updates.
/// </summary>
public sealed class TenBandEqualizerSampleProvider : ISampleProvider
{
    // 1.0-octave bandwidth => Q = 1 / (2 * sinh(ln(2) / 2)) ≈ 1.41421356
    private const float BandQ = 1.41421356f;

    private readonly ISampleProvider _source;
    private readonly object _lock = new();
    private readonly int _channels;
    private readonly int _sampleRate;
    private readonly BiQuadFilter[,] _filters;
    private readonly bool[] _bandActive;

    private bool _enabled;
    private float _preampGain = 1.0f;
    private EqSettings _settings;

    public TenBandEqualizerSampleProvider(ISampleProvider source, EqSettings? initialSettings = null)
    {
        _source = source ?? throw new ArgumentNullException(nameof(source));
        _channels = Math.Max(1, source.WaveFormat.Channels);
        _sampleRate = Math.Max(1, source.WaveFormat.SampleRate);

        int bandCount = EqSettings.Frequencies.Length;
        _filters = new BiQuadFilter[_channels, bandCount];
        _bandActive = new bool[bandCount];

        for (int ch = 0; ch < _channels; ch++)
        {
            for (int b = 0; b < bandCount; b++)
            {
                float freq = EqSettings.Frequencies[b];
                float safeFreq = Math.Min(freq, _sampleRate * 0.45f);
                _filters[ch, b] = BiQuadFilter.PeakingEQ(_sampleRate, safeFreq, BandQ, 0f);
            }
        }

        _settings = (initialSettings ?? new EqSettings()).Clone();
        ApplySettingsLocked(_settings);
    }

    public WaveFormat WaveFormat => _source.WaveFormat;

    public bool IsEnabled
    {
        get { lock (_lock) return _enabled; }
    }

    public float PreampLinearGain
    {
        get { lock (_lock) return _preampGain; }
    }

    public EqSettings GetSettingsSnapshot()
    {
        lock (_lock) return _settings.Clone();
    }

    public void Update(EqSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        lock (_lock)
        {
            _settings = settings.Clone();
            ApplySettingsLocked(_settings);
        }
    }

    private void ApplySettingsLocked(EqSettings settings)
    {
        _enabled = settings.Enabled;
        float clampedPreamp = Math.Clamp(settings.PreampDb, EqSettings.MinGainDb, EqSettings.MaxGainDb);
        _preampGain = MathF.Pow(10f, clampedPreamp / 20f);

        int bandCount = EqSettings.Frequencies.Length;
        for (int b = 0; b < bandCount; b++)
        {
            float freq = EqSettings.Frequencies[b];
            // Skip bands at or above Nyquist to keep the biquad filter numerically stable.
            if (freq >= _sampleRate * 0.49f)
            {
                _bandActive[b] = false;
                continue;
            }

            float rawGain = b < settings.Gains.Length ? settings.Gains[b] : 0f;
            float gain = Math.Clamp(rawGain, EqSettings.MinGainDb, EqSettings.MaxGainDb);
            _bandActive[b] = MathF.Abs(gain) > 0.001f;

            for (int ch = 0; ch < _channels; ch++)
            {
                _filters[ch, b].SetPeakingEq(_sampleRate, freq, BandQ, gain);
            }
        }
    }

    public int Read(float[] buffer, int offset, int count)
    {
        int samplesRead = _source.Read(buffer, offset, count);
        if (samplesRead <= 0) return samplesRead;

        lock (_lock)
        {
            if (!_enabled) return samplesRead;

            int bandCount = EqSettings.Frequencies.Length;
            float preamp = _preampGain;

            for (int i = 0; i < samplesRead; i++)
            {
                int ch = i % _channels;
                float sample = buffer[offset + i] * preamp;

                for (int b = 0; b < bandCount; b++)
                {
                    if (_bandActive[b])
                    {
                        sample = _filters[ch, b].Transform(sample);
                    }
                }

                buffer[offset + i] = sample;
            }
        }

        return samplesRead;
    }
}
