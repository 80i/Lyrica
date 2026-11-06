package net.lyrica.v1_21_11.song;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.lyrica.v1_21_11.Env;
import net.lyrica.v1_21_11.look.LookStore;

/**
 * Reads Windows' System Media Transport Controls through a small PowerShell helper, because the JVM has no direct way
 * to the WinRT session manager. The helper prints one tab-separated line on every change and at least every 2 s.
 *
 * <p>Some players, Spotify among them, publish a new song's title at once and its timeline only much later. Until then
 * the timeline still describes the last song, so the helper counts from the moment the title changed and reports the
 * length as unknown, so the card can show the time but not yet fill its wave, and goes back to the player's own numbers as soon as it publishes new ones.
 */
public final class MediaReader {

  private static final long SILENCE_LIMIT_MS = 20_000L;
  private static final long STEADY_WITHIN_MS = 400L;
  private static final long MAX_BACKOFF_MS = 60_000L;
  private static final int FIELDS = 9;

  private volatile Path onlyFile;
  private volatile boolean onlyWritten;

  private static final String SCRIPT = """
      param([int]$OwnerPid = 0, [string]$OnlyFile = '')
      $ErrorActionPreference = 'Stop'
      $stdout = [Console]::OpenStandardOutput()
      $utf8 = New-Object System.Text.UTF8Encoding($false)
      function Emit([string]$text) {
        $bytes = $utf8.GetBytes($text + "`n")
        $stdout.Write($bytes, 0, $bytes.Length)
        $stdout.Flush()
      }
      function Clean($text) {
        if ($null -eq $text) { return '' }
        return ([string]$text) -replace "[`t`r`n]", ' '
      }
      Add-Type -AssemblyName System.Runtime.WindowsRuntime
      Add-Type -AssemblyName System.Drawing
      $managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
      $propertiesType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
      $streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
      $inputType = [Windows.Storage.Streams.IInputStream, Windows.Storage.Streams, ContentType = WindowsRuntime]
      $asStream = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@($inputType))
      $asTask = $null
      foreach ($method in [System.WindowsRuntimeSystemExtensions].GetMethods()) {
        if ($method.Name -eq 'AsTask' -and $method.IsGenericMethodDefinition -and $method.GetParameters().Count -eq 1 -and $method.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1') {
          $asTask = $method
          break
        }
      }
      function Await($operation, [Type]$type) {
        $task = $asTask.MakeGenericMethod($type).Invoke($null, @($operation))
        if (-not $task.Wait(3000)) { throw 'timed out' }
        return $task.Result
      }
      function ReadArt($thumbnail) {
        if ($null -eq $thumbnail) { return '' }
        $source = $null; $image = $null; $bitmap = $null; $canvas = $null
        try {
          $source = Await ($thumbnail.OpenReadAsync()) $streamType
          # The stream arrives as a bare COM wrapper with no members, so it can only be reached through its interface.
          $bytes = New-Object System.IO.MemoryStream
          $asStream.Invoke($null, @($source)).CopyTo($bytes)
          if ($bytes.Length -eq 0) { return '' }
          $bytes.Position = 0
          $image = [System.Drawing.Image]::FromStream($bytes)
          $scale = [Math]::Min(1.0, 256.0 / [Math]::Max($image.Width, $image.Height))
          $width = [Math]::Max(1, [int]($image.Width * $scale))
          $height = [Math]::Max(1, [int]($image.Height * $scale))
          $bitmap = New-Object System.Drawing.Bitmap($width, $height)
          $canvas = [System.Drawing.Graphics]::FromImage($bitmap)
          $canvas.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
          $canvas.DrawImage($image, 0, 0, $width, $height)
          $out = New-Object System.IO.MemoryStream
          $bitmap.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
          return [Convert]::ToBase64String($out.ToArray())
        } catch {
          return ''
        } finally {
          if ($null -ne $canvas) { $canvas.Dispose() }
          if ($null -ne $bitmap) { $bitmap.Dispose() }
          if ($null -ne $image) { $image.Dispose() }
        }
      }
      $manager = Await ($managerType::RequestAsync()) $managerType
      $sinceEmit = [Diagnostics.Stopwatch]::StartNew()
      $lastState = $null
      $lastPosition = 0.0
      $lastPlaying = $false
      $lastSong = ''
      $lastArt = ''
      $artChecks = 0
      $tick = 0
      $lastStamp = $null
      $sinceStamp = [Diagnostics.Stopwatch]::StartNew()
      $sinceSong = [Diagnostics.Stopwatch]::StartNew()
      $staleTimeline = $false
      $staleEnd = 0.0
      while ($true) {
        $tick++
        if ($OwnerPid -gt 0 -and ($tick % 4) -eq 0) {
          try { $null = [Diagnostics.Process]::GetProcessById($OwnerPid) } catch { exit 0 }
        }
        $state = 'N'
        $head = 'N'
        $position = 0.0
        $playing = $false
        $art = ''
        try {
          $session = $null
          # With the file there, any other app is passed over here, before anything of what it plays is read
          $only = $OnlyFile -ne '' -and (Test-Path -LiteralPath $OnlyFile)
          foreach ($candidate in $manager.GetSessions()) {
            if ($only -and $candidate.SourceAppUserModelId -notlike '*spotify*') { continue }
            if ($candidate.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { $session = $candidate; break }
          }
          if ($null -eq $session) {
            $session = $manager.GetCurrentSession()
            if ($only -and $null -ne $session -and $session.SourceAppUserModelId -notlike '*spotify*') { $session = $null }
          }
          if ($null -ne $session) {
            $properties = Await ($session.TryGetMediaPropertiesAsync()) $propertiesType
            $title = Clean $properties.Title
            if ($title -ne '') {
              $artist = Clean $properties.Artist
              $album = Clean $properties.AlbumTitle
              $app = Clean $session.SourceAppUserModelId
              $playing = $session.GetPlaybackInfo().PlaybackStatus -eq 'Playing'
              $timeline = $session.GetTimelineProperties()
              $end = $timeline.EndTime.TotalMilliseconds
              $position = $timeline.Position.TotalMilliseconds
              if ($playing -and $timeline.LastUpdatedTime.Year -ge 2000) {
                $position += [Math]::Max(0.0, ([DateTimeOffset]::Now - $timeline.LastUpdatedTime).TotalMilliseconds)
              }
              if ($end -gt 0 -and $position -gt $end) { $position = $end }
              $stamp = "$($timeline.LastUpdatedTime.Ticks)/$($timeline.Position.Ticks)/$($timeline.EndTime.Ticks)"
              if ($stamp -cne $lastStamp) {
                $lastStamp = $stamp
                $sinceStamp.Restart()
                # The player repeats itself every few seconds, and the last song's timeline said again is no news
                if ($end -ne $staleEnd -or $timeline.Position.TotalMilliseconds -lt ($sinceSong.ElapsedMilliseconds + 3000)) {
                  $staleTimeline = $false
                }
              }
              $song = "$artist`t$title"
              if ($song -ne $lastSong) {
                # A timeline published just before the title belongs to the new song already
                $staleTimeline = $lastSong -ne '' -and $sinceStamp.ElapsedMilliseconds -gt 1000
                $staleEnd = $end
                $sinceSong.Restart()
                $lastSong = $song
                $lastArt = ''
                $artChecks = 16
              }
              if ($staleTimeline) {
                $end = 0.0
                $position = 0.0
                # Never quite nothing, which is how a player without a timeline reads
                if ($playing) { $position = [Math]::Max(1.0, [double]$sinceSong.ElapsedMilliseconds) }
              }
              # Players publish the cover a moment after the title, sometimes first showing the old one.
              if ($artChecks -gt 0 -and (($artChecks % 2) -eq 0 -or $lastArt -eq '')) {
                $read = ReadArt $properties.Thumbnail
                if ($read -ne '' -and $read -ne $lastArt) {
                  $lastArt = $read
                  $art = $read
                }
              }
              if ($artChecks -gt 0) { $artChecks-- }
              $state = "$app`t$playing`t$end`t$song`t$album"
              $flag = '0'
              if ($playing) { $flag = '1' }
              $head = "P`t$app`t$flag`t" + [long]$position + "`t" + [long]$end + "`t$title`t$artist`t$album"
            }
          }
        } catch {
          $state = $lastState
          $head = $null
        }
        if ($null -ne $head) {
          $expected = $lastPosition
          if ($lastPlaying) { $expected += $sinceEmit.ElapsedMilliseconds }
          $jumped = [Math]::Abs($position - $expected) -gt 800
          if ($state -cne $lastState -or $art -ne '' -or $jumped -or $sinceEmit.ElapsedMilliseconds -ge 2000) {
            if ($head -eq 'N') { Emit 'N' } else { Emit "$head`t$art" }
            $lastState = $state
            $lastPosition = $position
            $lastPlaying = $playing
            $sinceEmit.Restart()
          }
        } elseif ($sinceEmit.ElapsedMilliseconds -ge 2000) {
          Emit 'E'
          $sinceEmit.Restart()
        }
        Start-Sleep -Milliseconds 250
      }
      """;

  private volatile Thread thread;
  private volatile Process process;
  private volatile long lastLineMs;
  private volatile NowPlaying current;
  private int nothingLines;

  /** Only Windows has these controls; elsewhere nothing ever starts. */
  public static boolean supported() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
  }

  public NowPlaying current() {
    return this.current;
  }

  public synchronized void start() {
    if (this.thread != null || !supported()) {
      return;
    }

    Thread worker = new Thread(this::run, "Lyrica media");
    worker.setDaemon(true);
    this.thread = worker;
    worker.start();
  }

  public synchronized void stop() {
    Thread worker = this.thread;
    this.thread = null;
    this.current = null;
    Process running = this.process;
    if (running != null) {
      running.destroyForcibly();
    }

    if (worker != null) {
      worker.interrupt();
    }
  }

  /** Ends a helper that went quiet, which unblocks the worker and makes it start a new one. */
  /** Whether the player chose to have nothing read but Spotify. */
  private static boolean onlySpotifyWanted() {
    return "SPOTIFY".equals(LookStore.get().mediaSources);
  }

  private void writeOnly(boolean on) throws IOException {
    this.onlyWritten = on;
    if (on) {
      Files.writeString(this.onlyFile, "");
    } else {
      Files.deleteIfExists(this.onlyFile);
    }
  }

  /** Tells the helper when the choice changes, without file work on the game's thread. */
  private void followOnly() {
    boolean wanted = onlySpotifyWanted();
    if (this.onlyFile == null || wanted == this.onlyWritten) {
      return;
    }

    this.onlyWritten = wanted;
    Thread writer = new Thread(() -> {
      try {
        this.writeOnly(wanted);
      } catch (IOException e) {
        Env.LOG.debug("Could not change the source choice: {}", e.toString());
      }
    }, "Lyrica source choice");
    writer.setDaemon(true);
    writer.start();
    if (wanted && this.current != null && !isSpotify(this.current.sourceApp())) {
      this.current = null;
    }
  }

  private static boolean isSpotify(String app) {
    return app != null && app.toLowerCase(Locale.ROOT).contains("spotify");
  }

  public void keepAlive() {
    this.followOnly();
    Process running = this.process;
    if (running != null && System.currentTimeMillis() - this.lastLineMs > SILENCE_LIMIT_MS) {
      Env.LOG.warn("The media helper went silent, restarting it");
      this.lastLineMs = System.currentTimeMillis();
      running.destroyForcibly();
    }
  }

  private void run() {
    Thread self = Thread.currentThread();
    int failures = 0;
    while (this.thread == self) {
      boolean spoke = false;
      try {
        Process started = this.launch();
        this.process = started;
        // stop() may have come while launching, when it could not yet see the process.
        if (this.thread != self) {
          started.destroyForcibly();
          return;
        }

        spoke = this.readLines(started);
        started.destroyForcibly();
        if (this.thread == self) {
          Env.LOG.warn("The media helper ended");
        }
      } catch (IOException e) {
        if (this.thread == self) {
          Env.LOG.warn("The media helper failed: {}", e.toString());
        }
      } finally {
        this.process = null;
      }

      if (this.thread != self) {
        return;
      }

      failures = spoke ? 0 : failures + 1;
      if (!spoke) {
        this.current = null;
      }

      try {
        Thread.sleep(Math.min(MAX_BACKOFF_MS, 2_000L << Math.min(failures, 6)));
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private Process launch() throws IOException {
    Path folder = Env.dir().resolve("media");
    Files.createDirectories(folder);
    Path script = folder.resolve("reader.ps1");
    long owner = ProcessHandle.current().pid();
    Path pidFile = folder.resolve("reader-" + owner + ".pid");
    endStrayHelpers(script, pidFile, owner);
    Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);

    List<String> command = new ArrayList<>();
    command.add(powerShell());
    command.add("-NoProfile");
    command.add("-NonInteractive");
    command.add("-ExecutionPolicy");
    command.add("Bypass");
    command.add("-WindowStyle");
    command.add("Hidden");
    command.add("-File");
    command.add(script.toAbsolutePath().toString());
    command.add("-OwnerPid");
    command.add(Long.toString(owner));
    this.onlyFile = folder.resolve("only-spotify-" + owner);
    this.onlyFile.toFile().deleteOnExit();
    this.writeOnly(onlySpotifyWanted());
    command.add("-OnlyFile");
    command.add(this.onlyFile.toAbsolutePath().toString());

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectError(Redirect.to(folder.resolve("reader.log").toFile()));
    Process started = builder.start();
    // powershell.exe never runs the script while its stdin pipe is open.
    started.getOutputStream().close();
    this.lastLineMs = System.currentTimeMillis();
    rememberHelper(pidFile, started);
    return started;
  }

  private static String powerShell() {
    String root = System.getenv("SystemRoot");
    if (root != null) {
      File full = new File(root, "System32/WindowsPowerShell/v1.0/powershell.exe");
      if (full.isFile()) {
        return full.getPath();
      }
    }

    return "powershell.exe";
  }

  /**
   * Windows does not expose other processes' command lines to {@link ProcessHandle}, so besides looking there the
   * helper's pid and start time are kept in a file; the start time guards against a reused pid. Only this game's own: two
   * games open at once share this folder, and each ending the other's helper had them restarting every few seconds. A
   * helper whose game has gone ends by itself.
   */
  private static void endStrayHelpers(Path script, Path pidFile, long owner) {
    String scriptPath = script.toAbsolutePath().toString().toLowerCase(Locale.ROOT);
    String ownerArgument = "-ownerpid " + owner;
    Iterator<ProcessHandle> all = ProcessHandle.allProcesses().iterator();
    while (all.hasNext()) {
      ProcessHandle handle = all.next();
      Optional<String> line = handle.info().commandLine();
      String lower = line.isPresent() ? line.get().toLowerCase(Locale.ROOT) : "";
      if (lower.contains(scriptPath) && lower.endsWith(ownerArgument)) {
        handle.destroyForcibly();
      }
    }

    try {
      if (!Files.exists(pidFile)) {
        return;
      }

      String[] parts = Files.readString(pidFile, StandardCharsets.UTF_8).strip().split(" ");
      Optional<ProcessHandle> handle = ProcessHandle.of(Long.parseLong(parts[0]));
      if (handle.isPresent() && parts.length > 1) {
        Optional<Instant> started = handle.get().info().startInstant();
        if (started.isPresent() && started.get().toEpochMilli() == Long.parseLong(parts[1])) {
          handle.get().destroyForcibly();
        }
      }
    } catch (IOException | RuntimeException e) {
      Env.LOG.debug("Could not read the media helper's pid file: {}", e.toString());
    }
  }

  private static void rememberHelper(Path pidFile, Process started) {
    try {
      Optional<Instant> at = started.info().startInstant();
      String text = started.pid() + " " + (at.isPresent() ? at.get().toEpochMilli() : 0L);
      Files.writeString(pidFile, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      Env.LOG.debug("Could not write the media helper's pid file: {}", e.toString());
    }
  }

  private boolean readLines(Process from) throws IOException {
    boolean spoke = false;
    try (BufferedReader lines = new BufferedReader(new InputStreamReader(from.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = lines.readLine()) != null) {
        spoke = true;
        this.lastLineMs = System.currentTimeMillis();
        this.onLine(line);
      }
    }

    return spoke;
  }

  private void onLine(String line) {
    if ("N".equals(line)) {
      // Players drop their session for a moment between songs; one report of nothing must not end the song.
      if (++this.nothingLines >= 2) {
        this.current = null;
      }
      return;
    }

    String[] fields = line.split("\t", -1);
    if (fields.length < FIELDS || !"P".equals(fields[0])) {
      return;
    }

    long position;
    long duration;
    try {
      position = Long.parseLong(fields[3]);
      duration = Long.parseLong(fields[4]);
    } catch (NumberFormatException e) {
      return;
    }

    // Also here, for the moment a helper takes to hear of the choice
    if (onlySpotifyWanted() && !isSpotify(fields[1])) {
      this.current = null;
      return;
    }

    this.nothingLines = 0;
    long now = System.currentTimeMillis();
    NowPlaying before = this.current;
    boolean playing = "1".equals(fields[2]);
    NowPlaying reading = new NowPlaying(fields[5], fields[6], fields[7], duration, position, now, playing, fields[1], decodeArt(fields[8]));
    boolean sameSong = before != null && before.key().equals(reading.key());
    if (!sameSong) {
      this.current = reading;
      return;
    }

    long running = before.positionNow(0L);
    long steady = Math.abs(position - running) <= STEADY_WITHIN_MS ? running : position;
    byte[] art = reading.artPng() != null ? reading.artPng() : before.artPng();
    this.current = new NowPlaying(reading.title(), reading.artist(), reading.album(), duration, steady, now, playing, reading.sourceApp(), art);
  }

  private static byte[] decodeArt(String base64) {
    if (base64.isEmpty()) {
      return null;
    }

    try {
      return Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
