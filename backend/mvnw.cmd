@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup batch script, version 3.3.2
@REM ----------------------------------------------------------------------------

@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)
@SET (__MVNW_CMD__)=
@SET (__MVNW_ERROR__)=
@SET (__MVNW_MAVEN_CMD_LINE_ARGS__)=%MAVEN_CMD_LINE_ARGS%
@SET MAVEN_CMD_LINE_ARGS=%*

@IF NOT "%MVNW_USERNAME%"=="" (
  @SET MVNW_REPOURL=
)

@SET __MVNW_WRAPPER_JAR__=%~dp0.mvn\wrapper\maven-wrapper.jar
@SET __MVNW_WRAPPER_PROPS__=%~dp0.mvn\wrapper\maven-wrapper.properties

@REM Execute the wrapper
@"%JAVA_HOME%\bin\java.exe" %MAVEN_OPTS% %MAVEN_DEBUG_OPTS% -classpath "%__MVNW_WRAPPER_JAR__%" "-Dmaven.multiModuleProjectDirectory=%__MVNW_PROJECT_BASEDIR__%" org.apache.maven.wrapper.MavenWrapperMain %MAVEN_CMD_LINE_ARGS% %WRAPPER_LAUNCHER% %MAVEN_OPTS%

@SET MAVEN_CMD_LINE_ARGS=%__MVNW_MAVEN_CMD_LINE_ARGS__%
