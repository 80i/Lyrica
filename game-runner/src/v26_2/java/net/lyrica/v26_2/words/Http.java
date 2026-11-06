package net.lyrica.v26_2.words;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import net.lyrica.v26_2.Env;

/** Plain GET requests with an honest User-Agent. Only ever called from worker threads. */
public final class Http {

  private static final String USER_AGENT = "Lyrica/" + Env.VERSION;
  private static final HttpClient CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(8))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  // The HTTP client repeats request headers when it follows a redirect, and a token must reach the one address only.
  private static final HttpClient TOKEN_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(8))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();

  private Http() {
  }

  public static Reply get(String url) throws IOException {
    return send(CLIENT, url, null);
  }

  /** A GET that carries the session token. For the account service only. */
  public static Reply getWithToken(String url, String token) throws IOException {
    return send(TOKEN_CLIENT, url, token);
  }

  /** A GET whose answer may not be longer than this. A longer one is an IOException, and is read no further. */
  public static Reply getAtMost(String url, int mostBytes) throws IOException {
    try {
      HttpResponse<InputStream> response = CLIENT.send(request(url, null), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        byte[] bytes = body.readNBytes(mostBytes + 1);
        if (bytes.length > mostBytes) {
          throw new IOException("Answer too long");
        }

        return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), -1L);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted", e);
    }
  }

  private static HttpRequest request(String url, String token) throws IOException {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(12))
          .header("User-Agent", USER_AGENT)
          .header("Accept", "application/json");
      if (token != null) {
        builder.header("Authorization", "Bearer " + token);
      }

      return builder.GET().build();
    } catch (IllegalArgumentException e) {
      throw new IOException("Bad address", e);
    }
  }

  private static Reply send(HttpClient client, String url, String token) throws IOException {
    HttpRequest request = request(url, token);
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      long wait = response.headers().firstValue("Retry-After").map(Http::seconds).orElse(-1L);
      return new Reply(response.statusCode(), response.body(), wait);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted", e);
    }
  }

  /** A plain number of seconds. The other form, a date, counts as not said. */
  private static long seconds(String value) {
    try {
      return Long.parseLong(value.strip());
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  /** @param retryAfterSeconds what the server's Retry-After says, or -1 */
  public record Reply(int status, String body, long retryAfterSeconds) {

  }
}
