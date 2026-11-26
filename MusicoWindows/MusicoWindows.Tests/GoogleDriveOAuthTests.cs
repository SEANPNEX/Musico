using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Cloud;
using Xunit;

namespace MusicoWindows.Tests;

file sealed class InMemoryDriveOAuthStorage : IGoogleDriveOAuthStorage
{
    private GoogleDriveOAuthRecord _record;

    public InMemoryDriveOAuthStorage(GoogleDriveOAuthRecord? initial = null)
    {
        _record = initial ?? new GoogleDriveOAuthRecord();
    }

    public GoogleDriveOAuthRecord LoadRecord() => _record;
    public void SaveRecord(GoogleDriveOAuthRecord record) => _record = record;
}

file sealed class MockOAuthHandler : HttpMessageHandler
{
    private readonly Func<HttpRequestMessage, HttpResponseMessage> _handler;

    public MockOAuthHandler(Func<HttpRequestMessage, HttpResponseMessage> handler)
    {
        _handler = handler;
    }

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
        Task.FromResult(_handler(request));
}

public sealed class GoogleDriveOAuthTests
{
    [Fact]
    public void PkceAuthUrlAndCallbackParsing_Work()
    {
        var pkce = GoogleDriveOAuthManager.GeneratePkce();
        Assert.DoesNotContain("=", pkce.CodeVerifier);
        Assert.DoesNotContain("+", pkce.CodeVerifier);
        Assert.DoesNotContain("/", pkce.CodeVerifier);
        Assert.DoesNotContain("=", pkce.CodeChallenge);
        Assert.NotEqual(pkce.CodeVerifier, pkce.CodeChallenge);

        string authUrl = GoogleDriveOAuthManager.BuildAuthorizationUrl(
            "cid-win.apps.googleusercontent.com",
            "http://127.0.0.1:51234/oauth2callback",
            "state_win",
            pkce.CodeChallenge);
        var uri = new Uri(authUrl);
        Assert.Equal("accounts.google.com", uri.Host);
        Assert.Contains("client_id=cid-win.apps.googleusercontent.com", uri.Query);
        Assert.Contains("code_challenge_method=S256", uri.Query);
        Assert.Contains("state=state_win", uri.Query);

        var cb = GoogleDriveOAuthManager.ParseCallbackRequest(
            "GET /oauth2callback?code=code_win_1&state=state_win HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
        Assert.NotNull(cb);
        Assert.Equal("code_win_1", cb!.Code);
        Assert.Equal("state_win", cb.State);
    }

    [Fact]
    public async Task GetValidAccessTokenAsync_RefreshesExpiredTokenAndPreservesRefreshToken()
    {
        var storage = new InMemoryDriveOAuthStorage(new GoogleDriveOAuthRecord
        {
            ClientId = "cid-1",
            ClientSecret = "csec-1",
            AccessToken = "old_tok",
            RefreshToken = "ref_1",
            ExpiresAtEpochMs = 1000
        });
        using var handler = new MockOAuthHandler(req =>
        {
            Assert.Equal(GoogleDriveOAuthManager.TokenEndpoint, req.RequestUri!.AbsoluteUri);
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent("{\"access_token\":\"fresh_tok\",\"expires_in\":3600}", Encoding.UTF8, "application/json")
            };
        });

        var manager = new GoogleDriveOAuthManager(storage, handler);
        string? token = await manager.GetValidAccessTokenAsync();
        Assert.Equal("fresh_tok", token);
        Assert.Equal("ref_1", storage.LoadRecord().RefreshToken);
    }

    [Fact]
    public async Task GoogleDriveClient_UsesOAuthBearerAndRetriesOn401()
    {
        int streamCalls = 0;
        using var handler = new MockOAuthHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            string? auth = req.Headers.Authorization?.ToString();

            if (url.Contains("/drive/v3/files/folder123?", StringComparison.Ordinal))
            {
                Assert.Equal("Bearer tok_1", auth);
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(
                        "{\"id\":\"folder123\",\"name\":\"Private Win Album\",\"mimeType\":\"application/vnd.google-apps.folder\"}",
                        Encoding.UTF8,
                        "application/json")
                };
            }
            if (url.Contains("/drive/v3/files?", StringComparison.Ordinal))
            {
                Assert.Equal("Bearer tok_1", auth);
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(
                        "{\"files\":[{\"id\":\"trackWIN12345\",\"name\":\"01 Theme.flac\",\"mimeType\":\"audio/flac\"}]}",
                        Encoding.UTF8,
                        "application/json")
                };
            }
            if (url.Contains("/drive/v3/files/trackWIN12345?alt=media", StringComparison.Ordinal))
            {
                streamCalls++;
                if (streamCalls == 1)
                {
                    Assert.Equal("Bearer tok_1", auth);
                    return new HttpResponseMessage(HttpStatusCode.Unauthorized);
                }
                Assert.Equal("Bearer tok_2", auth);
                var ok = new HttpResponseMessage(HttpStatusCode.PartialContent)
                {
                    Content = new ByteArrayContent([0x66, 0x4C, 0x61, 0x43])
                };
                ok.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("audio/flac");
                return ok;
            }
            throw new InvalidOperationException("Unexpected URL: " + url);
        });

        var client = new GoogleDriveClient(
            handler: handler,
            oauthTokenProvider: (forceRefresh, _) => Task.FromResult<string?>(forceRefresh ? "tok_2" : "tok_1"));

        var playlist = await client.FetchPlaylistAsync("https://drive.google.com/drive/folders/folder123");
        Assert.Equal("Private Win Album", playlist.Title);
        var item = Assert.Single(playlist.Items);
        Assert.Equal("01 Theme", item.Title);

        var stream = await client.ResolveAudioStreamAsync(item.RemoteTrackId);
        Assert.NotNull(stream.Headers);
        Assert.Equal("Bearer tok_2", stream.Headers["Authorization"]);
        Assert.Equal(".flac", stream.Extension);
        Assert.Contains("alt=media", stream.Url);
    }
}
