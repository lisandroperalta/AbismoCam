param(
    [ValidateRange(1, 65535)][int]$Port = 9000,
    [int]$Count = 0,
    [int]$TimeoutMs = 0
)
$ErrorActionPreference = 'Stop'
$receiver = [System.Net.Sockets.UdpClient]::new()
$receiver.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
$receiver.Client.Bind([System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, $Port))
if ($TimeoutMs -gt 0) { $receiver.Client.ReceiveTimeout = $TimeoutMs }
$remote = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
Write-Output "Escuchando OSC UDP en 0.0.0.0:$Port. Ctrl+C para terminar."
Write-Output 'Esperado: /camara/disparo ,i 1 al iniciar el conteo; ,i 0 al finalizar cinco segundos despues.'
$received = 0
try {
    while ($Count -eq 0 -or $received -lt $Count) {
        $packet = $receiver.Receive([ref]$remote)
        $received++
        try {
            $end = [Array]::IndexOf($packet, [byte]0)
            if ($end -lt 1) { throw 'Direccion OSC invalida' }
            $address = [Text.Encoding]::ASCII.GetString($packet, 0, $end)
            $tagStart = ([int][Math]::Floor($end / 4) + 1) * 4
            if ($packet.Length -lt $tagStart + 8) { throw 'Paquete incompleto' }
            if ($packet[$tagStart] -ne 44 -or $packet[$tagStart + 1] -ne 105) { throw 'Se esperaba tipo OSC ,i' }
            $offset = $tagStart + 4
            $valueBytes = [byte[]]@($packet[$offset + 3], $packet[$offset + 2], $packet[$offset + 1], $packet[$offset])
            $value = [BitConverter]::ToInt32($valueBytes, 0)
            $time = [DateTime]::Now.ToString('HH:mm:ss.fff')
            Write-Output "$time  $remote  $address ,i $value"
        } catch { Write-Warning "Paquete no reconocido de ${remote}: $($_.Exception.Message)" }
    }
} finally { $receiver.Close() }
