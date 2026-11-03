# Contributing to cross-clip

Thank you for your interest in contributing to `cross-clip`.

## Code of Conduct

Please maintain a respectful, welcoming, and collaborative environment.

## How to Contribute

### Reporting Bugs
- Ensure the bug was not already reported by searching existing GitHub Issues.
- Provide clear steps to reproduce the issue, your operating system version, Android version, and device model.
- Include sanitized logs where appropriate (never share your master secret or pairing codes).

### Pull Requests
1. Fork the repository and create a new feature branch (`git checkout -b feature/your-feature-name`).
2. Adhere to existing code conventions:
   - Windows: C# / .NET 8 idioms and styling.
   - Android: Kotlin coding standards and lifecycle-aware architecture.
   - Server: TypeScript strict mode with minimal external dependencies.
3. Verify all test suites pass before submitting:
   - Windows: `dotnet test windows/CrossClip.Tests/CrossClip.Tests.csproj`
   - Android: `./gradlew testReleaseUnitTest` inside `android/`
   - Server: `npm test` inside `server/`
4. Submit your pull request against the `main` branch with a clear description of your changes.

## Security Disclosures

If you discover a security vulnerability, please do not disclose it publicly on the issue tracker. Open a private security advisory or contact the maintainers directly.
