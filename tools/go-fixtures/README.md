# go-fixtures

`main.go` marshals sample values of the wavelength SDK DTOs with Go's
`encoding/json`. The JSON it prints is exactly what the gomobile facade
returns, and it is used as golden input for the Kotlin codec tests.

`types.go` is not checked in. `scripts/generate-models.sh` writes it from the
pinned release (`kotlin-modelgen -go-types`), runs this program, and writes
`wavelength-core/src/commonTest/.../GoFixtures.kt`.
