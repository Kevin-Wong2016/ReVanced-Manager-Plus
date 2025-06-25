@echo off
echo 🚀 Quick Dependency Update Check
echo ================================
gradlew.bat dependencyUpdates
echo.
echo ✅ Check completed! 
echo 📝 Now update versions in gradle/libs.versions.toml
pause 