set JAVA_HOME=C:\Program Files\Java\jdk-21.0.10
set PATH=%JAVA_HOME%\bin;%PATH%

mvnw -U -DforceContextQualifier=latest -Didempiere-karaf.version=14.0.0.latest clean verify 

pause
