$datasetPath = "dataset.jsonl"
$mlServiceUrl = "http://localhost:8000/classify"

$mismatches = @()
$lowMargins = @()

$lines = Get-Content $datasetPath
foreach ($line in $lines) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    
    $data = $line | ConvertFrom-Json
    $query = $data.query
    $expected = $data.expected_difficulty

    $body = @{ text = $query } | ConvertTo-Json
    
    try {
        $response = Invoke-RestMethod -Uri $mlServiceUrl -Method Post -ContentType "application/json" -Body $body
        $actual = $response.complexity
        $margin = $response.margin
        
        if ($null -eq $margin) { $margin = 0.0 }

        if ($actual -ne $expected) {
            $mismatches += [PSCustomObject]@{ Query = $query; Expected = $expected; Actual = $actual; Margin = $margin }
        } elseif ($margin -lt 0.02) {
            $lowMargins += [PSCustomObject]@{ Query = $query; Expected = $expected; Actual = $actual; Margin = $margin }
        }
    } catch {
        Write-Host "Error classifying: $query"
    }
}

Write-Host "`n=== MISMATCHES ($($mismatches.Count)) ===" -ForegroundColor Red
foreach ($m in $mismatches) {
    $formattedMargin = "{0:N3}" -f $m.Margin
    Write-Host "[EXPECTED $($m.Expected) -> CLASSIFIED $($m.Actual)] (Margin: $formattedMargin) | $($m.Query)"
}

Write-Host "`n=== LOW MARGIN CORRECT CLASSES (< 0.02) ($($lowMargins.Count)) ===" -ForegroundColor Yellow
foreach ($m in $lowMargins) {
    $formattedMargin = "{0:N4}" -f $m.Margin
    Write-Host "[$($m.Actual)] Margin: $formattedMargin | $($m.Query)"
}
