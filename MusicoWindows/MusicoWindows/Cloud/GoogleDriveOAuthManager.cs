using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace MusicoWindows.Cloud;

public sealed record GoogleDriveOAuthRecord
{
    public string ClientId { get; init; } = string.Empty;
    public string ClientSecret { get; init; } = string.Empty;
    public string AccessToken { get; init; } = string.Empty;
    public string RefreshToken { get; init; } = string.Empty;
    public long ExpiresAtEpochMs { get; init; }
    public string? AccountEmail { get; init; }
}

public sealed record GoogleDrivePkcePair(string CodeVerifier, string CodeChallenge);

public sealed record GoogleDriveOAuthCallback(
    string? Code,
    string? State,
    string? Error,
    string? ErrorDescription);

public sealed record GoogleDriveTokenResponse(
    string AccessToken,
    string? RefreshToken,
    double ExpiresInSeconds);

public sealed record GoogleDriveOAuthState(
    string ClientId,
    string ClientSecret,
    bool IsSignedIn,
    string? AccountEmail,
    bool IsSigningIn,
    string? ErrorMessage);

public interface IGoogleDriveOAuthStorage
{
    GoogleDriveOAuthRecord LoadRecord();
    void SaveRecord(GoogleDriveOAuthRecord record);
}

public sealed class DpapiGoogleDriveOAuthStorage : IGoogleDriveOAuthStorage
{
    private readonly string _filePath;
    private readonly object _lock = new();

    public DpapiGoogleDriveOAuthStorage(string stateDirectory)
    {
        Directory.CreateDirectory(stateDirectory);
        _filePath = Path.Combine(stateDirectory, "gdrive-oauth.dat");
    }

    public GoogleDriveOAuthRecord LoadRecord()
    {
        lock (_lock)
        {
            if (!File.Exists(_filePath))
            {
                return new GoogleDriveOAuthRecord();
            }
            try
            {
                byte[] raw = File.ReadAllBytes(_filePath);
                byte[] plain = OperatingSystem.IsWindows()
                    ? ProtectedData.Unprotect(raw, null, DataProtectionScope.CurrentUser)
                    : raw;
                return JsonSerializer.Deserialize<GoogleDriveOAuthRecord>(plain) ?? new GoogleDriveOAuthRecord();
            }
            catch
            {
                return new GoogleDriveOAuthRecord();
            }
        }
    }

    public void SaveRecord(GoogleDriveOAuthRecord record)
    {
        lock (_lock)
        {
            try
            {
                byte[] plain = JsonSerializer.SerializeToUtf8Bytes(record);
                byte[] stored = OperatingSystem.IsWindows()
                    ? ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser)
                    : plain;
                File.WriteAllBytes(_filePath, stored);
            }
            catch
            {
                // Ignore persistence errors
            }
        }
    }
}

public sealed class GoogleDriveOAuthException : Exception
{
    public bool IsRevokedOrInvalid { get; }

    public GoogleDriveOAuthException(string message, bool isRevokedOrInvalid = false)
        : base(message)
    {
        IsRevokedOrInvalid = isRevokedOrInvalid;
    }
}

public sealed class GoogleDriveOAuthManager
{
    public const string DriveReadonlyScope =
        "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/userinfo.email";
    public const string AuthEndpoint = "https://accounts.google.com/o/oauth2/v2/auth";
    public const string TokenEndpoint = "https://oauth2.googleapis.com/token";
    public const string UserInfoEndpoint = "https://www.googleapis.com/oauth2/v3/userinfo";

    private readonly IGoogleDriveOAuthStorage _storage;
    private readonly HttpMessageHandler? _handler;
    private readonly SemaphoreSlim _tokenLock = new(1, 1);

    private GoogleDriveOAuthRecord _record;
    private bool _isSigningIn;
    private string? _errorMessage;

    public event EventHandler<GoogleDriveOAuthState>? StateChanged;

    public GoogleDriveOAuthManager(string stateDirectory, HttpMessageHandler? handler = null)
        : this(new DpapiGoogleDriveOAuthStorage(stateDirectory), handler)
    {
    }

    public GoogleDriveOAuthManager(IGoogleDriveOAuthStorage storage, HttpMessageHandler? handler = null)
    {
        _storage = storage;
        _handler = handler;
        _record = _storage.LoadRecord();
    }

    public GoogleDriveOAuthState CurrentState => new(
        ClientId: _record.ClientId,
        ClientSecret: _record.ClientSecret,
        IsSignedIn: !string.IsNullOrWhiteSpace(_record.AccessToken) || !string.IsNullOrWhiteSpace(_record.RefreshToken),
        AccountEmail: _record.AccountEmail,
        IsSigningIn: _isSigningIn,
        ErrorMessage: _errorMessage);

    public void UpdateClientCredentials(string clientId, string clientSecret)
    {
        _record = _record with
        {
            ClientId = (clientId ?? string.Empty).Trim(),
            ClientSecret = (clientSecret ?? string.Empty).Trim()
        };
        _storage.SaveRecord(_record);
        NotifyStateChanged();
    }

    public void SignOut()
    {
        _record = _record with
        {
            AccessToken = string.Empty,
            RefreshToken = string.Empty,
            ExpiresAtEpochMs = 0,
            AccountEmail = null
        };
        _errorMessage = null;
        _storage.SaveRecord(_record);
        NotifyStateChanged();
    }

    public async Task<string?> GetValidAccessTokenAsync(bool forceRefresh = false, CancellationToken ct = default)
    {
        await _tokenLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (!forceRefresh &&
                !string.IsNullOrWhiteSpace(_record.AccessToken) &&
                _record.ExpiresAtEpochMs > nowMs + 60_000)
            {
                return _record.AccessToken;
            }

            if (string.IsNullOrWhiteSpace(_record.RefreshToken) || string.IsNullOrWhiteSpace(_record.ClientId))
            {
                if (!string.IsNullOrWhiteSpace(_record.AccessToken) && _record.ExpiresAtEpochMs > nowMs + 5_000)
                {
                    return _record.AccessToken;
                }
                return null;
            }

            try
            {
                var refreshed = await RefreshAccessTokenAsync(
                    _record.ClientId,
                    string.IsNullOrWhiteSpace(_record.ClientSecret) ? null : _record.ClientSecret,
                    _record.RefreshToken,
                    _handler,
                    ct).ConfigureAwait(false);

                _record = _record with
                {
                    AccessToken = refreshed.AccessToken,
                    RefreshToken = !string.IsNullOrWhiteSpace(refreshed.RefreshToken)
                        ? refreshed.RefreshToken!
                        : _record.RefreshToken,
                    ExpiresAtEpochMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() +
                                       (long)(Math.Max(60.0, refreshed.ExpiresInSeconds) * 1000.0)
                };
                _errorMessage = null;
                _storage.SaveRecord(_record);
                NotifyStateChanged();
                return _record.AccessToken;
            }
            catch (GoogleDriveOAuthException ex) when (ex.IsRevokedOrInvalid)
            {
                SignOut();
                throw;
            }
        }
        finally
        {
            _tokenLock.Release();
        }
    }

    public async Task StartSignInAsync(CancellationToken ct = default)
    {
        string cleanClientId = (_record.ClientId ?? string.Empty).Trim();
        string cleanSecret = (_record.ClientSecret ?? string.Empty).Trim();
        if (cleanClientId.Length == 0)
        {
            _errorMessage = "Enter a Google Cloud OAuth Client ID first.";
            NotifyStateChanged();
            return;
        }

        _isSigningIn = true;
        _errorMessage = null;
        NotifyStateChanged();

        try
        {
            var pkce = GeneratePkce();
            string expectedState = RandomBase64Url(24);

            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            try
            {
                int port = ((IPEndPoint)listener.LocalEndpoint).Port;
                string redirectUri = $"http://127.0.0.1:{port}/oauth2callback";
                string authUrl = BuildAuthorizationUrl(cleanClientId, redirectUri, expectedState, pkce.CodeChallenge);

                Process.Start(new ProcessStartInfo(authUrl) { UseShellExecute = true });

                using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeoutCts.CancelAfter(TimeSpan.FromMinutes(3));

                GoogleDriveOAuthCallback? callback = null;
                while (callback is null)
                {
                    using var client = await listener.AcceptTcpClientAsync(timeoutCts.Token).ConfigureAwait(false);
                    using var stream = client.GetStream();
                    using var reader = new StreamReader(stream, Encoding.UTF8, leaveOpen: true);
                    string? requestLine = await reader.ReadLineAsync(timeoutCts.Token).ConfigureAwait(false);
                    var parsed = ParseCallbackRequest(requestLine ?? string.Empty);

                    string html = "<!doctype html><html><body style=\"font-family:Segoe UI,sans-serif;text-align:center;padding:48px;\"><h2>Musico</h2><p>Google Drive sign-in complete. You can close this browser tab and return to Musico.</p></body></html>";
                    byte[] htmlBytes = Encoding.UTF8.GetBytes(html);
                    string responseHeaders = $"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {htmlBytes.Length}\r\nConnection: close\r\n\r\n";
                    byte[] headerBytes = Encoding.UTF8.GetBytes(responseHeaders);
                    await stream.WriteAsync(headerBytes, timeoutCts.Token).ConfigureAwait(false);
                    await stream.WriteAsync(htmlBytes, timeoutCts.Token).ConfigureAwait(false);
                    await stream.FlushAsync(timeoutCts.Token).ConfigureAwait(false);

                    if (parsed is not null && (!string.IsNullOrEmpty(parsed.Code) || !string.IsNullOrEmpty(parsed.Error)))
                    {
                        callback = parsed;
                    }
                }

                if (!string.IsNullOrWhiteSpace(callback.Error))
                {
                    throw new GoogleDriveOAuthException(callback.ErrorDescription ?? callback.Error!);
                }
                if (!string.Equals(callback.State, expectedState, StringComparison.Ordinal))
                {
                    throw new GoogleDriveOAuthException("OAuth state validation failed.");
                }
                if (string.IsNullOrWhiteSpace(callback.Code))
                {
                    throw new GoogleDriveOAuthException("Google sign-in was cancelled.");
                }

                var tokens = await ExchangeAuthorizationCodeAsync(
                    cleanClientId,
                    string.IsNullOrWhiteSpace(cleanSecret) ? null : cleanSecret,
                    callback.Code!,
                    pkce.CodeVerifier,
                    redirectUri,
                    _handler,
                    ct).ConfigureAwait(false);

                string? email = await FetchUserEmailAsync(tokens.AccessToken, _handler, ct).ConfigureAwait(false);

                await _tokenLock.WaitAsync(ct).ConfigureAwait(false);
                try
                {
                    _record = _record with
                    {
                        ClientId = cleanClientId,
                        ClientSecret = cleanSecret,
                        AccessToken = tokens.AccessToken,
                        RefreshToken = !string.IsNullOrWhiteSpace(tokens.RefreshToken)
                            ? tokens.RefreshToken!
                            : _record.RefreshToken,
                        ExpiresAtEpochMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() +
                                           (long)(Math.Max(60.0, tokens.ExpiresInSeconds) * 1000.0),
                        AccountEmail = email
                    };
                    _storage.SaveRecord(_record);
                }
                finally
                {
                    _tokenLock.Release();
                }
            }
            finally
            {
                listener.Stop();
            }
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            _errorMessage = "Google sign-in timed out or was cancelled.";
        }
        catch (OperationCanceledException)
        {
            _errorMessage = "Google sign-in was cancelled.";
        }
        catch (Exception ex)
        {
            _errorMessage = ex.Message;
        }
        finally
        {
            _isSigningIn = false;
            NotifyStateChanged();
        }
    }

    private void NotifyStateChanged() => StateChanged?.Invoke(this, CurrentState);

    public static string Base64UrlEncode(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static string RandomBase64Url(int byteCount)
    {
        byte[] bytes = RandomNumberGenerator.GetBytes(Math.Max(16, byteCount));
        return Base64UrlEncode(bytes);
    }

    public static GoogleDrivePkcePair GeneratePkce()
    {
        string verifier = RandomBase64Url(32);
        byte[] digest = SHA256.HashData(Encoding.UTF8.GetBytes(verifier));
        return new GoogleDrivePkcePair(verifier, Base64UrlEncode(digest));
    }

    public static string BuildAuthorizationUrl(
        string clientId,
        string redirectUri,
        string state,
        string codeChallenge)
    {
        var pairs = new (string K, string V)[]
        {
            ("client_id", clientId),
            ("redirect_uri", redirectUri),
            ("response_type", "code"),
            ("scope", DriveReadonlyScope),
            ("access_type", "offline"),
            ("prompt", "consent"),
            ("code_challenge_method", "S256"),
            ("code_challenge", codeChallenge),
            ("state", state)
        };
        var sb = new StringBuilder(AuthEndpoint);
        sb.Append('?');
        for (int i = 0; i < pairs.Length; i++)
        {
            if (i > 0) sb.Append('&');
            sb.Append(Uri.EscapeDataString(pairs[i].K));
            sb.Append('=');
            sb.Append(Uri.EscapeDataString(pairs[i].V));
        }
        return sb.ToString();
    }

    public static GoogleDriveOAuthCallback? ParseCallbackRequest(string rawHttpRequest)
    {
        if (string.IsNullOrWhiteSpace(rawHttpRequest)) return null;
        string firstLine = rawHttpRequest.Split('\n')[0].Trim();
        string[] parts = firstLine.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 2) return null;
        if (!Uri.TryCreate("http://127.0.0.1" + parts[1], UriKind.Absolute, out var uri)) return null;

        var query = new Dictionary<string, string>(StringComparer.Ordinal);
        string rawQuery = uri.Query.TrimStart('?');
        if (rawQuery.Length > 0)
        {
            foreach (string pair in rawQuery.Split('&', StringSplitOptions.RemoveEmptyEntries))
            {
                int eq = pair.IndexOf('=');
                string key = eq >= 0 ? Uri.UnescapeDataString(pair[..eq]) : Uri.UnescapeDataString(pair);
                string val = eq >= 0 ? Uri.UnescapeDataString(pair[(eq + 1)..]) : string.Empty;
                if (key.Length > 0) query[key] = val;
            }
        }

        query.TryGetValue("code", out string? code);
        query.TryGetValue("state", out string? state);
        query.TryGetValue("error", out string? err);
        query.TryGetValue("error_description", out string? errDesc);
        return new GoogleDriveOAuthCallback(code, state, err, errDesc);
    }

    public static Task<GoogleDriveTokenResponse> ExchangeAuthorizationCodeAsync(
        string clientId,
        string? clientSecret,
        string code,
        string codeVerifier,
        string redirectUri,
        HttpMessageHandler? handler = null,
        CancellationToken ct = default)
    {
        var list = new List<KeyValuePair<string, string>>
        {
            new("grant_type", "authorization_code"),
            new("client_id", clientId),
            new("code", code),
            new("code_verifier", codeVerifier),
            new("redirect_uri", redirectUri)
        };
        if (!string.IsNullOrWhiteSpace(clientSecret))
        {
            list.Add(new("client_secret", clientSecret));
        }
        return PostTokenRequestAsync(list, handler, ct);
    }

    public static Task<GoogleDriveTokenResponse> RefreshAccessTokenAsync(
        string clientId,
        string? clientSecret,
        string refreshToken,
        HttpMessageHandler? handler = null,
        CancellationToken ct = default)
    {
        var list = new List<KeyValuePair<string, string>>
        {
            new("grant_type", "refresh_token"),
            new("client_id", clientId),
            new("refresh_token", refreshToken)
        };
        if (!string.IsNullOrWhiteSpace(clientSecret))
        {
            list.Add(new("client_secret", clientSecret));
        }
        return PostTokenRequestAsync(list, handler, ct);
    }

    private static async Task<GoogleDriveTokenResponse> PostTokenRequestAsync(
        List<KeyValuePair<string, string>> formParams,
        HttpMessageHandler? handler,
        CancellationToken ct)
    {
        using var http = CloudHttpHelper.CreateClient(null, handler);
        using var req = new HttpRequestMessage(HttpMethod.Post, TokenEndpoint)
        {
            Content = new FormUrlEncodedContent(formParams)
        };
        using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        string jsonText = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);

        using var doc = string.IsNullOrWhiteSpace(jsonText) ? null : JsonDocument.Parse(jsonText);
        if (doc is not null && doc.RootElement.ValueKind == JsonValueKind.Object)
        {
            if (doc.RootElement.TryGetProperty("error", out var errEl) &&
                errEl.ValueKind == JsonValueKind.String &&
                !string.IsNullOrWhiteSpace(errEl.GetString()))
            {
                string errCode = errEl.GetString()!;
                string desc = doc.RootElement.TryGetProperty("error_description", out var descEl) &&
                              descEl.ValueKind == JsonValueKind.String
                    ? (descEl.GetString() ?? errCode)
                    : errCode;
                bool revoked = errCode is "invalid_grant" or "unauthorized_client" || resp.StatusCode == HttpStatusCode.Unauthorized;
                throw new GoogleDriveOAuthException(desc, isRevokedOrInvalid: revoked);
            }

            if (resp.IsSuccessStatusCode &&
                doc.RootElement.TryGetProperty("access_token", out var tokEl) &&
                tokEl.ValueKind == JsonValueKind.String &&
                !string.IsNullOrWhiteSpace(tokEl.GetString()))
            {
                string accessToken = tokEl.GetString()!;
                string? refreshToken = doc.RootElement.TryGetProperty("refresh_token", out var refEl) &&
                                       refEl.ValueKind == JsonValueKind.String
                    ? refEl.GetString()
                    : null;
                double expiresIn = doc.RootElement.TryGetProperty("expires_in", out var expEl) &&
                                   expEl.TryGetDouble(out double expVal)
                    ? expVal
                    : 3600.0;
                return new GoogleDriveTokenResponse(accessToken, refreshToken, expiresIn);
            }
        }

        throw new GoogleDriveOAuthException($"Google OAuth token request failed (HTTP {(int)resp.StatusCode}).");
    }

    public static async Task<string?> FetchUserEmailAsync(
        string accessToken,
        HttpMessageHandler? handler = null,
        CancellationToken ct = default)
    {
        try
        {
            using var http = CloudHttpHelper.CreateClient(null, handler);
            using var req = new HttpRequestMessage(HttpMethod.Get, UserInfoEndpoint);
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
            using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;
            string jsonText = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(jsonText);
            if (doc.RootElement.TryGetProperty("email", out var emailEl) &&
                emailEl.ValueKind == JsonValueKind.String)
            {
                string? email = emailEl.GetString();
                return string.IsNullOrWhiteSpace(email) ? null : email.Trim();
            }
        }
        catch
        {
        }
        return null;
    }
}
