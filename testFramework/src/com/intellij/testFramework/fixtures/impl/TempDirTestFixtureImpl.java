/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */

package com.intellij.testFramework.fixtures.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * @author Dmitry Avdeev
 */
public class TempDirTestFixtureImpl extends BaseFixture implements TempDirTestFixture {
  private final ArrayList<File> myFilesToDelete = new ArrayList<File>();
  private File myTempDir;

  public VirtualFile copyFile(VirtualFile file) {
    try {
      createTempDirectory();
      VirtualFile tempDir =
        LocalFileSystem.getInstance().refreshAndFindFileByPath(myTempDir.getCanonicalPath().replace(File.separatorChar, '/'));
      return VfsUtil.copyFile(this, file, tempDir);
    }
    catch (IOException e) {
      throw new RuntimeException("Cannot copy " + file, e);
    }
  }

  public VirtualFile copyAll(final String dataDir, final String targetDir) {
    createTempDirectory();
    return ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
      public VirtualFile compute() {
        try {
          VirtualFile tempDir =
            LocalFileSystem.getInstance().refreshAndFindFileByPath(myTempDir.getCanonicalPath().replace(File.separatorChar, '/'));
          if (targetDir.length() > 0) {
            assert !targetDir.contains("/") : "nested directories not implemented";
            VirtualFile child = tempDir.findChild(targetDir);
            if (child == null) {
              child = tempDir.createChildDirectory(this, targetDir);
            }
            tempDir = child;
          }
          final VirtualFile from = LocalFileSystem.getInstance().refreshAndFindFileByPath(dataDir);
          assert from != null : dataDir + " not found";
          VfsUtil.copyDirectory(null, from, tempDir, null);
          return tempDir;
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });
  }

  public String getTempDirPath() {
    return createTempDirectory().getAbsolutePath();
  }

  @Nullable
  public VirtualFile getFile(final String path) {

    final Ref<VirtualFile> result = new Ref<VirtualFile>(null);
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        try {
          final String fullPath = myTempDir.getCanonicalPath().replace(File.separatorChar, '/') + "/" + path;
          final VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);
          result.set(file);
        }
        catch (IOException e) {
          assert false : "Cannot find " + path + ": " + e;
        }
      }
    });
    return result.get();
  }

  @NotNull
  public VirtualFile createFile(final String name) {
    final File file = createTempDirectory();
    return ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
      public VirtualFile compute() {
        final File file1 = new File(file, name);
        FileUtil.createIfDoesntExist(file1);
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file1);
      }
    });
  }

  @NotNull
  public VirtualFile createFile(final String name, final String text) throws IOException {
    final VirtualFile file = createFile(name);
    VfsUtil.saveText(file, text);
    return file;
  }

  public void setUp() throws Exception {
    super.setUp();
    createTempDirectory();
  }

  public void tearDown() throws Exception {
    for (final File fileToDelete : myFilesToDelete) {
      boolean deleted = FileUtil.delete(fileToDelete);
      assert deleted : "Can't delete "+fileToDelete;
    }
    super.tearDown();
  }

  protected File createTempDirectory() {
    try {
      if (myTempDir == null) {
        myTempDir = FileUtil.createTempDirectory("unitTest", null);
        myFilesToDelete.add(myTempDir);
      }
      return myTempDir;
    }
    catch (IOException e) {
      throw new RuntimeException("Cannot create temp dir", e);
    }
  }

}
