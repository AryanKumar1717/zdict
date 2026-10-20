# fetch-pokeapi.ps1
# Fetches JSON responses from PokeAPI (ids 1-300 for pokemon and species)
# and saves to benchmark-data/real/<group>/<seq>.json with 150 ms delay.

param(
    [string]$OutputDir = "../benchmark-data/real",
    [int]$MaxId = 300,
    [int]$DelayMs = 150
)

$ErrorActionPreference = "Stop"

Write-Host "Starting PokeAPI fetch..."
Write-Host "Output Directory: $OutputDir"
Write-Host "Max ID: $MaxId"
Write-Host "Delay: $DelayMs ms"

mvn compile exec:java -D"exec.mainClass=dev.zdict.PokeApiFetcher" -D"exec.args=$OutputDir $MaxId $DelayMs"
