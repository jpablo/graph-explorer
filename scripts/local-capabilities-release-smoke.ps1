# Windows publish gate for the desktop + gx release binaries.
#
# SHARED ON PURPOSE. This lived inline in two workflows -- release-binaries.yml
# and local-capabilities-smoke.yml -- and the copies drifted twice: P2 converted
# only the smoke copy to D1 content hashes, so the release copy went on
# asserting `revision -ne 1` and failed v0.9.0 with
#
#     watch revision: 04ba7dc099a297de100b1cbd3143b0f7171e3d96d9965529a1b65cd246ab3d66
#
# The macOS/Linux gate never drifted, and the only reason is that it has always
# been one file (local-capabilities-release-smoke.sh) called from both places.
# Nothing but duplication distinguished the two cases, so the duplication is
# what got removed.

# Drives the desktop's control channel directly, rather than through
# `gx`: this is the DESKTOP's publish gate, and routing it through the
# reference client would test one client's view of the contract
# instead of the contract.
#
# .NET speaks AF_UNIX natively (UnixDomainSocketEndPoint), which is
# the third independent client of this protocol after the Rust server
# and the Scala `gx` -- and the reason P5 chose one transport for all
# platforms instead of D4's named pipe on Windows.
$desktop = "desktop/src-tauri/target/release/graph-explorer-desktop.exe"
$gx = "gx-cli/target/gx.exe"
if (-not (Test-Path $desktop)) { throw "missing desktop binary: $desktop" }
if (-not (Test-Path $gx)) { throw "missing gx binary: $gx" }
$control = Join-Path $HOME ".graph-explorer/runtime/control.json"

$script:rpcId = 0
function Invoke-Control($method, $params) {
  $c = Get-Content $control -Raw | ConvertFrom-Json
  if (-not $c.socket) { throw "no socket recorded in $control" }

  $client = New-Object System.Net.Sockets.Socket(
    [System.Net.Sockets.AddressFamily]::Unix,
    [System.Net.Sockets.SocketType]::Stream,
    [System.Net.Sockets.ProtocolType]::Unspecified)
  try {
    # A stale socket file outlives a crashed desktop, so connecting is
    # the only thing that proves one is there.
    $client.Connect((New-Object System.Net.Sockets.UnixDomainSocketEndPoint($c.socket)))

    $script:rpcId++
    $frame = @{ id = $script:rpcId; method = $method; params = $params } |
      ConvertTo-Json -Compress -Depth 6
    # UTF-8 named explicitly: Windows' default charset is
    # windows-1252, and under D1 the bytes ARE the revision (V-16).
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($frame + "`n")
    [void]$client.Send($bytes)

    # Frames are newline-delimited; accumulate then decode ONCE, since
    # a recv can split mid-character.
    #
    # A MemoryStream, and no array slicing. `$chunk[0..($n-1)]` on a
    # byte[] produces System.Object[] in PowerShell — not byte[] — and
    # List[byte].AddRange rejects it. That cost a CI run, and it failed
    # as "the desktop never came up" because the readiness loop
    # swallowed the exception. Stream.Write(buffer, offset, count) has
    # no coercion step to get wrong.
    $stream = New-Object System.IO.MemoryStream
    $chunk = New-Object byte[] 65536
    $bytes = $null
    $newline = -1
    while ($true) {
      $n = $client.Receive($chunk)
      if ($n -le 0) { break }
      $stream.Write($chunk, 0, $n)
      $bytes = $stream.ToArray()
      $newline = [Array]::IndexOf([byte[]]$bytes, [byte]10)
      if ($newline -ge 0) { break }
    }
    if ($newline -lt 0) { throw "no response frame for '$method'" }
    $line = [System.Text.Encoding]::UTF8.GetString($bytes, 0, $newline)
    return $line | ConvertFrom-Json
  }
  finally { $client.Dispose() }
}

Get-Process graph-explorer-desktop -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Remove-Item "$HOME/.graph-explorer/runtime/control.json" -ErrorAction SilentlyContinue
Remove-Item "$HOME/.graph-explorer/runtime/control.sock" -ErrorAction SilentlyContinue

# The desktop's own output is captured, because the previous run failed
# here with "did not become ready" and NOTHING else: the readiness loop
# swallowed every exception, so a bind failure, a crash and a slow
# start were indistinguishable. Same lesson as the helper's
# `2>/dev/null` — the evidence was being discarded by the thing whose
# job was to report it.
$outLog = Join-Path $env:TEMP "gx-desktop-out.log"
$errLog = Join-Path $env:TEMP "gx-desktop-err.log"
$desktopProc = Start-Process -FilePath $desktop -PassThru `
  -RedirectStandardOutput $outLog -RedirectStandardError $errLog
try {
  # BUDGET: 120s, not the 30s this used to allow.
  #
  # The desktop binds its control socket inside tauri's `setup`, which runs
  # only after WebView2 has initialized. On a cold Windows runner that
  # initialization has a long tail, and the socket cannot exist before it
  # finishes -- so this wait is really "how long may WebView2 take".
  #
  # Measured across recent CI runs, the whole step:
  #
  #     4s  4s  4s  4s  4s  4s  5s  5s  7s  8s  15s  31s   <- passes
  #     33s                                                <- the failure
  #
  # The common case is ~4s and the tail is not close to it. A 30s budget sat
  # INSIDE that tail, so the gate failed on the desktop being slow rather than
  # broken -- and it is a blocking gate on every PR, where a false red teaches
  # people to ignore it. 120s is four times the worst observed start; the loop
  # exits the moment the channel answers, so the common case still costs 4s.
  #
  # Deadline-based rather than a fixed iteration count: an iteration is a
  # connect attempt plus a sleep, so `120 iterations` was never 30 seconds of
  # anything in particular.
  $runtimeDir = Join-Path $HOME ".graph-explorer/runtime"
  $waitStarted = Get-Date
  $deadline = $waitStarted.AddSeconds(120)
  $ready = $false
  $lastError = "(never attempted)"
  $socketAppearedAfter = $null

  while ((Get-Date) -lt $deadline) {
    # A dead process will never become ready. Waiting the full budget for one
    # that exited in the first 200ms tells you nothing.
    if ($desktopProc.HasExited) { break }

    # Record WHEN the socket shows up, separately from when it answers. The
    # failure this replaces reported neither, so "never bound" and "bound but
    # not answering" looked identical -- and they have different causes.
    if (-not $socketAppearedAfter) {
      $sock = Get-ChildItem $runtimeDir -Force -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -eq "control.sock" }
      if ($sock) { $socketAppearedAfter = [math]::Round(((Get-Date) - $waitStarted).TotalSeconds, 1) }
    }

    if (Test-Path $control) {
      try {
        $status = Invoke-Control "status" @{}
        if ($status.ok -and $status.result.running) { $ready = $true; break }
        $lastError = "answered but not running: $($status | ConvertTo-Json -Compress)"
      } catch { $lastError = $_.Exception.Message }
    } else {
      $lastError = "control file not written yet"
    }
    Start-Sleep -Milliseconds 250
  }
  $waited = [math]::Round(((Get-Date) - $waitStarted).TotalSeconds, 1)
  if ($ready) { Write-Host "control channel ready after ${waited}s" }
  if (-not $ready) {
    Write-Host "--- why the control channel never came up ---"
    Write-Host "waited         : ${waited}s of a 120s budget"
    if ($socketAppearedAfter) {
      Write-Host "control.sock   : appeared after ${socketAppearedAfter}s but never answered"
    } else {
      Write-Host "control.sock   : NEVER APPEARED -- the desktop did not reach tauri's setup"
    }
    Write-Host "desktop exited : $($desktopProc.HasExited)"
    if ($desktopProc.HasExited) { Write-Host "exit code      : $($desktopProc.ExitCode)" }
    Write-Host "last error     : $lastError"
    Write-Host "runtime dir    : $runtimeDir"
    if (Test-Path $runtimeDir) {
      # Get-ChildItem rather than Test-Path on the socket: an AF_UNIX
      # socket on Windows is a reparse point, and Test-Path's answer
      # for one is not something to bet a diagnosis on.
      Get-ChildItem $runtimeDir -Force | ForEach-Object { Write-Host "  $($_.Name)  $($_.Length)" }
    } else { Write-Host "  (absent)" }
    if (Test-Path $control) { Write-Host "control.json   : $(Get-Content $control -Raw)" }
    foreach ($log in @($outLog, $errLog)) {
      Write-Host "--- $log ---"
      if (Test-Path $log) { Get-Content $log | ForEach-Object { Write-Host "  $_" } }
    }
    throw "desktop control channel did not become ready"
  }

  # The credential is gone from the design (D4), and this is the file
  # that used to carry it.
  $raw = Get-Content $control -Raw | ConvertFrom-Json
  if ($raw.PSObject.Properties.Name -contains "token") { throw "runtime file still carries a token" }
  if ($raw.PSObject.Properties.Name -contains "port") { throw "runtime file still carries a port" }

  $tmp = Join-Path $env:TEMP ("gx-release-smoke-" + [guid]::NewGuid().ToString("N") + ".dot")
  @"
digraph G {
  a -> b
}
"@ | Set-Content -Path $tmp -NoNewline

  # D1: a revision IS the sha256 of the file's bytes. Hash the FILE
  # rather than the string we think we wrote — Set-Content's encoding
  # and line endings are exactly the sort of thing that differs on
  # Windows, and the desktop hashes what is on disk.
  function Sha256File([string]$path) {
    (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLower()
  }
  $seedHash = Sha256File $tmp

  # A Windows path is still the interesting case: the drive colon,
  # backslash separators, and any \?\ prefix. It used to be
  # interesting because a URL had to survive them; it is interesting
  # now because this is where the path crosses two languages' idea of
  # a JSON string.
  $watch = Invoke-Control "watch" @{ path = $tmp }
  if (-not $watch.ok) { throw "watch failed: $($watch.error.message)" }
  if ($watch.result.revision -ne $seedHash) { throw "watch revision: $($watch.result.revision) != $seedHash" }

  # The desktop answers with the CANONICAL path, which on this runner
  # is not the one we sent: $env:TEMP is the 8.3 short name
  # (C:\Users\RUNNER~1\...) and canonicalization resolves it to the
  # real one (C:\Users\runneradmin\...). That is V-13 working, not a
  # defect — so the assertion is that both calls RESOLVE THE SAME WAY,
  # not that the desktop echoes back what it was handed.
  $canonical = $watch.result.path
  if (-not $canonical) { throw "watch did not report a path" }

  $doc = Invoke-Control "get-document" @{ path = $tmp }
  if ($doc.result.document.revision -ne $seedHash) { throw "get revision: $($doc.result.document.revision) != $seedHash" }
  if ($doc.result.document.path -ne $canonical) {
    throw "watch and get disagree: '$canonical' vs '$($doc.result.document.path)'"
  }

  # And the stronger form of the same property, which is the one v1
  # actually broke: the CANONICAL spelling must reach the same registry
  # entry as the short one. A mangled path is how `watch` succeeded and
  # `get` returned 400 for five months.
  $viaCanonical = Invoke-Control "get-document" @{ path = $canonical }
  if ($viaCanonical.result.document.revision -ne $seedHash) {
    throw "the canonical path missed the watch registry: $($viaCanonical | ConvertTo-Json -Compress)"
  }

  $put = Invoke-Control "put-document" `
    @{ path = $tmp; text = "digraph G {`n  b -> c`n}`n"; baseRevision = $seedHash; source = "cli" }
  $afterHash = Sha256File $tmp
  if ($put.result.document.revision -ne $afterHash) {
    throw "set revision: $($put.result.document.revision) != $afterHash"
  }

  # A conflict is an error FRAME now, not an HTTP status. It is also
  # not an exception: the desktop answered.
  $stale = Invoke-Control "put-document" `
    @{ path = $tmp; text = "digraph G {`n  stale`n}`n"; baseRevision = $seedHash; source = "cli" }
  if ($stale.ok) { throw "a stale write should have been refused" }
  if ($stale.error.code -ne "DOCUMENT_CONFLICT") { throw "stale write code: $($stale.error.code)" }
  if ($stale.error.currentRevision -ne $afterHash) { throw "stale currentRevision: $($stale.error.currentRevision) != $afterHash" }

  $unwatch = Invoke-Control "unwatch" @{ path = $tmp }
  if ($unwatch.result.removed -ne $true) { throw "unwatch did not remove the watch" }

  # And that the shipped gx binary runs at all, headless -- plus that
  # its own socket client reaches the same desktop this step just did.
  & $gx status | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "gx status failed with exit code $LASTEXITCODE" }

  & $gx open $tmp | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "gx open failed with exit code $LASTEXITCODE" }

  Remove-Item $tmp -ErrorAction SilentlyContinue
}
finally {
  if ($desktopProc -and -not $desktopProc.HasExited) {
    Stop-Process -Id $desktopProc.Id -Force -ErrorAction SilentlyContinue
  }
}
