param(
    [Parameter(Mandatory = $true)][string]$TextFile,
    [Parameter(Mandatory = $true)][string]$OutWav,
    [string]$Voice = "",
    [int]$Rate = 0
)
$text = [System.IO.File]::ReadAllText($TextFile, [System.Text.Encoding]::UTF8)
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$sapi = $true
try {
    if ($Voice -ne "") {
        try { $s.SelectVoice($Voice) } catch { $sapi = $false }
    }
    if ($sapi) {
        $s.Rate = $Rate
        $s.SetOutputToWaveFile($OutWav)
        $s.Speak($text)
    }
} finally {
    $s.Dispose()
}

if (-not $sapi) {
    # Voice is a Windows "natural/OneCore" voice (e.g. Hindi): synthesize through WinRT.
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [void][Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime]
    [void][Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
    function Await($op, [Type]$type) {
        $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
        $t.Wait(-1) | Out-Null
        $t.Result
    }
    $synth = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer
    $v = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | Where-Object { $_.DisplayName -eq $Voice } | Select-Object -First 1
    if ($null -eq $v) { Write-Error "Voice not found: $Voice"; exit 1 }
    $synth.Voice = $v
    $stream = Await ($synth.SynthesizeTextToStreamAsync($text)) ([Windows.Media.SpeechSynthesis.SpeechSynthesisStream])
    $reader = New-Object Windows.Storage.Streams.DataReader($stream)
    [void](Await ($reader.LoadAsync([uint32]$stream.Size)) ([uint32]))
    $bytes = New-Object byte[] ([int]$stream.Size)
    $reader.ReadBytes($bytes)
    [System.IO.File]::WriteAllBytes($OutWav, $bytes)
}
