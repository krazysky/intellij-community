/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Executor {

  public static class ExecutionException extends RuntimeException {

    private final int myExitCode;
    @NotNull private final String myOutput;

    ExecutionException(int exitCode, @NotNull String output) {
      super("Failed with exit code " + exitCode);
      myExitCode = exitCode;
      myOutput = output;
    }

    public int getExitCode() {
      return myExitCode;
    }

    @NotNull
    public String getOutput() {
      return myOutput;
    }

  }

  private static String ourCurrentDir;

  private static void cdAbs(String absolutePath) {
    ourCurrentDir = absolutePath;
    debug("# cd " + shortenPath(absolutePath));
  }

  private static void cdRel(String relativePath) {
    cdAbs(ourCurrentDir + "/" + relativePath);
  }

  public static void cd(String relativeOrAbsolutePath) {
    if (relativeOrAbsolutePath.startsWith("/") || relativeOrAbsolutePath.charAt(1) == ':') {
      cdAbs(relativeOrAbsolutePath);
    }
    else {
      cdRel(relativeOrAbsolutePath);
    }
  }

  public static void cd(VirtualFile dir) {
    cd(dir.getPath());
  }

  public static String pwd() {
    return ourCurrentDir;
  }

  @NotNull
  public static File touch(String filePath) {
    try {
      File file = child(filePath);
      assert !file.exists() : "File " + file + " shouldn't exist yet";
      //noinspection ResultOfMethodCallIgnored
      new File(file.getParent()).mkdirs(); // ensure to create the directories
      boolean fileCreated = file.createNewFile();
      assert fileCreated;
      debug("# touch " + filePath);
      return file;
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  public static File touch(String fileName, String content) {
    File filePath = touch(fileName);
    echo(fileName, content);
    return filePath;
  }

  public static void echo(String fileName, String content) {
    try {
      FileUtil.writeToFile(child(fileName), content.getBytes(), true);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void overwrite(@NotNull File file, @NotNull String content) throws IOException {
    FileUtil.writeToFile(file, content.getBytes(), false);
  }

  public static void append(@NotNull File file, @NotNull String content) throws IOException {
    FileUtil.writeToFile(file, content.getBytes(), true);
  }

  @NotNull
  public static File mkdir(@NotNull String dirName) {
    File file = child(dirName);
    boolean dirMade = file.mkdir();
    assert dirMade;
    debug("# mkdir " + dirName);
    return file;
  }

  public static String cat(String fileName) {
    try {
      String content = FileUtil.loadFile(child(fileName));
      debug("# cat " + fileName);
      return content;
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void cp(String fileName, File destinationDir) {
    try {
      FileUtil.copy(child(fileName), new File(destinationDir, fileName));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected static String run(@NotNull List<String> params, boolean ignoreNonZeroExitCode) throws ExecutionException {
    final ProcessBuilder builder = new ProcessBuilder().command(params);
    builder.directory(ourCurrentDir());
    builder.redirectErrorStream(true);
    Process clientProcess;
    try {
      clientProcess = builder.start();
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }

    CapturingProcessHandler handler = new CapturingProcessHandler(clientProcess, CharsetToolkit.getDefaultSystemCharset());
    ProcessOutput result = handler.runProcess(30 * 1000);
    if (result.isTimeout()) {
      throw new RuntimeException("Timeout waiting for the command execution. Command: " + StringUtil.join(params, " "));
    }

    String stdout = result.getStdout().trim();
    if (result.getExitCode() != 0) {
      if (ignoreNonZeroExitCode) {
        debug("{" + result.getExitCode() + "}");
      }
      debug(stdout);
      if (!ignoreNonZeroExitCode) {
        throw new ExecutionException(result.getExitCode(), stdout);
      }
    }
    else {
      debug(stdout);
    }
    return stdout;
  }

  protected static List<String> splitCommandInParameters(String command) {
    List<String> split = new ArrayList<String>();

    boolean insideParam = false;
    StringBuilder currentParam = new StringBuilder();
    for (char c : command.toCharArray()) {
      boolean flush = false;
      if (insideParam) {
        if (c == '\'') {
          insideParam = false;
          flush = true;
        }
        else {
          currentParam.append(c);
        }
      }
      else if (c == '\'') {
        insideParam = true;
      }
      else if (c == ' ') {
        flush = true;
      }
      else {
        currentParam.append(c);
      }

      if (flush) {
        if (!StringUtil.isEmptyOrSpaces(currentParam.toString())) {
          split.add(currentParam.toString());
        }
        currentParam = new StringBuilder();
      }
    }

    // last flush
    if (!StringUtil.isEmptyOrSpaces(currentParam.toString())) {
      split.add(currentParam.toString());
    }
    return split;
  }

  protected static String findExecutable(String programName, String unixExec, String winExec, Collection<String> envs) {
    String exec = findEnvValue(programName, envs);
    if (exec != null) {
      return exec;
    }
    File fileExec = PathEnvironmentVariableUtil.findInPath(SystemInfo.isWindows ? winExec : unixExec);
    if (fileExec != null) {
      return fileExec.getAbsolutePath();
    }
    throw new IllegalStateException(programName + " executable not found. " + (envs.size() > 0 ?
                                                                               "Please define a valid environment variable " +
                                                                               envs.iterator().next() +
                                                                               " pointing to the " +
                                                                               programName +
                                                                               " executable." : ""));
  }

  protected static String findEnvValue(String programNameForLog, Collection<String> envs) {
    for (String env : envs) {
      String val = System.getenv(env);
      if (val != null && new File(val).canExecute()) {
        debug(String.format("Using %s from %s: %s", programNameForLog, env, val));
        return val;
      }
    }
    return null;
  }

  protected static void debug(String msg) {
    if (!StringUtil.isEmptyOrSpaces(msg)) {
      System.out.println(msg);
    }
  }

  private static String shortenPath(String path) {
    String[] split = path.split("/");
    if (split.length > 3) {
      // split[0] is empty, because the path starts from /
      return String.format("/%s/.../%s/%s", split[1], split[split.length - 2], split[split.length - 1]);
    }
    return path;
  }

  private static File child(String fileName) {
    assert ourCurrentDir != null : "Current dir hasn't been initialized yet. Call cd at least once before any other command.";
    return new File(ourCurrentDir, fileName);
  }

  private static File ourCurrentDir() {
    assert ourCurrentDir != null : "Current dir hasn't been initialized yet. Call cd at least once before any other command.";
    return new File(ourCurrentDir);
  }
}
