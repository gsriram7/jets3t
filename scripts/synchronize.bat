@echo off

REM Include libraries in classpath
set CP=jets3t.jar
set CP=%CP%;synchronize.jar
set CP=%CP%;libs/commons-logging/commons-logging-1.1.jar
set CP=%CP%;libs/commons-codec/commons-codec-1.3.jar
set CP=%CP%;libs/commons-httpclient/commons-httpclient-3.0.1.jar

java -classpath %CP% org.jets3t.apps.synchronize.Synchronize %*