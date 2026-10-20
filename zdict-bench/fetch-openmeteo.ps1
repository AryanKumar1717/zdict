# fetch-openmeteo.ps1
# Fetches JSON responses from Open-Meteo:
# - forecast: 300 varied coordinates across India
# - geocoding: 200 Indian and world cities
# Saves to benchmark-data/real/<group>/<seq>.json with 200 ms delay.

param(
    [string]$OutputDir = "../benchmark-data/real",
    [int]$ForecastCount = 300,
    [int]$GeocodingCount = 200,
    [int]$DelayMs = 200
)

$ErrorActionPreference = "Stop"

Write-Host "Starting Open-Meteo fetch..."
Write-Host "Output Directory: $OutputDir"
Write-Host "Forecast Count: $ForecastCount"
Write-Host "Geocoding Count: $GeocodingCount"
Write-Host "Delay: $DelayMs ms"

mvn compile exec:java -D"exec.mainClass=dev.zdict.OpenMeteoFetcher" -D"exec.args=$OutputDir $ForecastCount $GeocodingCount $DelayMs"
