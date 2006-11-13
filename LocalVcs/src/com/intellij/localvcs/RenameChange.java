package com.intellij.localvcs;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class RenameChange extends Change {
  private Path myPath;
  private String myNewName;
  private IdPath myAffectedEntryIdPath;

  public RenameChange(Path path, String newName) {
    myPath = path;
    myNewName = newName;
  }

  public RenameChange(Stream s) throws IOException {
    myPath = s.readPath();
    myNewName = s.readString();
  }

  @Override
  public void write(Stream s) throws IOException {
    s.writePath(myPath);
    s.writeString(myNewName);
  }

  public Path getPath() {
    return myPath;
  }

  public String getNewName() {
    return myNewName;
  }

  @Override
  public void applyTo(RootEntry root) {
    myAffectedEntryIdPath = root.getEntry(myPath).getIdPath();
    root.doRename(myPath, myNewName);
  }

  @Override
  public void revertOn(RootEntry root) {
    root.doRename(myPath.renamedWith(myNewName), myPath.getName());
  }

  @Override
  protected List<IdPath> getAffectedEntryIdPaths() {
    return Arrays.asList(myAffectedEntryIdPath);
  }
}
