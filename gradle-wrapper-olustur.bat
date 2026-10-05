@echo off
rem Gradle wrapper ayar dosyasini olusturur (Android Studio Gradle 8.10.2 kullansin diye)
cd /d "%~dp0"
if not exist gradle\wrapper mkdir gradle\wrapper
(
echo distributionBase=GRADLE_USER_HOME
echo distributionPath=wrapper/dists
echo distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
echo networkTimeout=10000
echo validateDistributionUrl=true
echo zipStoreBase=GRADLE_USER_HOME
echo zipStorePath=wrapper/dists
) > gradle\wrapper\gradle-wrapper.properties
echo Tamam: gradle\wrapper\gradle-wrapper.properties olusturuldu.
pause
