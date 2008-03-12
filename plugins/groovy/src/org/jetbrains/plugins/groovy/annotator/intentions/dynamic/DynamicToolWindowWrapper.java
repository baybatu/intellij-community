package org.jetbrains.plugins.groovy.annotator.intentions.dynamic;

import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider;
import com.intellij.refactoring.listeners.RefactoringListenerManager;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.FilterComponent;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.AbstractTableCellEditor;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import com.intellij.util.ui.treetable.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyBundle;
import org.jetbrains.plugins.groovy.annotator.intentions.QuickfixUtil;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.elements.DItemElement;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.elements.DMethodElement;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.elements.DPropertyElement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression;
import org.jetbrains.plugins.groovy.lang.psi.util.GrDynamicImplicitElement;
import org.jetbrains.plugins.groovy.lang.psi.util.GrDynamicImplicitMethodImpl;
import org.jetbrains.plugins.groovy.lang.psi.util.GrDynamicImplicitPropertyImpl;

import javax.swing.*;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

/**
 * User: Dmitry.Krasilschikov
 * Date: 09.01.2008
 */
public class DynamicToolWindowWrapper {

  public static final String DYNAMIC_TOOLWINDOW_ID = GroovyBundle.message("dynamic.tool.window.id");
  public static final String QUALIFIED_IDENTIFIER_REGEXP = "[a-zA-Z0-9(.)]+";
  private static JPanel myTreeTablePanel;
  private static JPanel myBigPanel;
  private static ListTreeTableModelOnColumns myTreeTableModel;

  private static final int CLASS_OR_ELEMENT_NAME_COLUMN = 0;
  private static final int TYPE_COLUMN = 1;

  private static String[] myColumnNames = {
      "Dynamic element",
      "Type"
  };

  private static TreeTable myTreeTable;

  private static boolean doesTreeTableInit() {
    return myBigPanel != null && myTreeTableModel != null && myTreeTablePanel != null;
  }

  public static TreeTable getTreeTable(ToolWindow window, Project project) {
    if (!doesTreeTableInit()) {
      reconfigureWindow(project, window);
    }

    return returnTreeTable();
  }

  public static void configureWindow(final Project project, ToolWindow window) {
    reconfigureWindow(project, window);
  }

  private static void reconfigureWindow(final Project project, ToolWindow window) {
    window.setTitle(GroovyBundle.message("toolwindow.dynamic.properties"));
    window.setToHideOnEmptyContent(true);

    DynamicManager.getInstance(project).addDynamicChangeListener(new DynamicChangeListener() {
      public void dynamicPropertyChange() {
        rebuildTreePanel(project);
      }
    });

    buildBigPanel(project);
    window.getComponent().add(getContentPane(project));
  }

  private static JPanel buildBigPanel(final Project project) {
    myBigPanel = new JPanel(new BorderLayout());
    myBigPanel.setBackground(UIUtil.getFieldForegroundColor());

    final DynamicFilterComponent filter = new DynamicFilterComponent(project, GroovyBundle.message("dynamic.toolwindow.property.fiter"), 10);
    filter.setBackground(UIUtil.getLabelBackground());

    myBigPanel.add(new Label(GroovyBundle.message("dynamic.toolwindow.search.elements")), BorderLayout.NORTH);
    myBigPanel.add(filter, BorderLayout.NORTH);

    myTreeTablePanel = new JPanel(new BorderLayout());
    rebuildTreePanel(project);

    myBigPanel.add(myTreeTablePanel);
    myBigPanel.setPreferredSize(new Dimension(200, myBigPanel.getHeight()));

    myBigPanel.revalidate();
    return myBigPanel;
  }

  public static void rebuildTreePanel(Project project) {
//    storeState(project);
//    if (!isDynamicToolWindowShowing()) return;

    DefaultMutableTreeNode myRootNode = new DefaultMutableTreeNode();
    buildTree(project, myRootNode);

    rebuildTreeView(project, myRootNode, false);

//    restoreState(project);
  }

  private static void rebuildTreeView(Project project, DefaultMutableTreeNode root, boolean expandAll) {
    PsiDocumentManager.getInstance(project).commitAllDocuments();
//    if (!isDynamicToolWindowShowing()) return;

//    storeState();
    myTreeTablePanel.removeAll();

    final JScrollPane treeTable = createTable(project, root);

    if (expandAll) {
      TreeUtil.expandAll(myTreeTable.getTree());
    }

    myTreeTablePanel.add(treeTable);
  }

  private static DefaultMutableTreeNode buildTree(Project project, DefaultMutableTreeNode rootNode) {
    final Collection<DClassElement> containingClasses = DynamicManager.getInstance(project).getAllContainingClasses();

    DefaultMutableTreeNode containingClassNode;
    for (DClassElement containingClassElement : containingClasses) {
      containingClassNode = new DefaultMutableTreeNode(containingClassElement);

      final Collection<DPropertyElement> properties = DynamicManager.getInstance(project).findDynamicPropertiesOfClass(containingClassElement.getName());

//      if (properties.length == 0) continue;

      DefaultMutableTreeNode propertyTreeNode;
      for (DPropertyElement property : properties) {

        propertyTreeNode = new DefaultMutableTreeNode(property);
        containingClassNode.add(propertyTreeNode);
      }

      DefaultMutableTreeNode methodTreeNode;
      final Set<DMethodElement> methods = containingClassElement.getMethods();

      for (DMethodElement methodElement : methods) {
        final String[] psiTypes = QuickfixUtil.getArgumentsTypes(methodElement.getPairs());

        final DMethodElement method = DynamicManager.getInstance(project).findConcreteDynamicMethod(containingClassElement.getName(), methodElement.getName(), psiTypes);

        methodTreeNode = new DefaultMutableTreeNode(method);
        containingClassNode.add(methodTreeNode);
      }

      rootNode.add(containingClassNode);
    }
    return rootNode;
  }

  private static JScrollPane createTable(final Project project, MutableTreeNode myTreeRoot) {
    ColumnInfo[] columnInfos = {new ClassColumnInfo(myColumnNames[CLASS_OR_ELEMENT_NAME_COLUMN]), new PropertyTypeColumnInfo(myColumnNames[TYPE_COLUMN])};

    myTreeTableModel = new ListTreeTableModelOnColumns(myTreeRoot, columnInfos);

    myTreeTable = new TreeTable(myTreeTableModel);

    final MyColoredTreeCellRenderer treeCellRenderer = new MyColoredTreeCellRenderer();
    final TreeTableCellRenderer tableCellRenderer = new TreeTableCellRenderer(myTreeTable, myTreeTable.getTree());
    tableCellRenderer.setCellRenderer(treeCellRenderer);

    myTreeTable.setTreeCellRenderer(treeCellRenderer);

    myTreeTable.setRootVisible(false);
    myTreeTable.setSelectionMode(DefaultTreeSelectionModel.CONTIGUOUS_TREE_SELECTION);

    final MyPropertyOrClassCellEditor propertyOrClassCellEditor = new MyPropertyOrClassCellEditor();
    final MyPropertyTypeCellEditor typeCellEditor = new MyPropertyTypeCellEditor();

    typeCellEditor.addCellEditorListener(new CellEditorListener() {
      public void editingStopped(ChangeEvent e) {
        final TreeTableTree tree = getTree();

        final String newTypeValue = ((MyPropertyTypeCellEditor) e.getSource()).getCellEditorValue();

        if (newTypeValue == null || tree == null) {
          myTreeTable.editingStopped(e);
          return;
        }

        final TreePath editingTypePath = tree.getSelectionPath();
        final TreePath editingClassPath = editingTypePath.getParentPath();

        Object oldTypeValue = myTreeTable.getValueAt(tree.getRowForPath(editingTypePath), TYPE_COLUMN);

        if (!(oldTypeValue instanceof String)) {
          myTreeTable.editingStopped(e);
          return;
        }

        final Object editingPropertyObject = myTreeTable.getValueAt(tree.getRowForPath(editingTypePath), CLASS_OR_ELEMENT_NAME_COLUMN);
        final Object editingClassObject = myTreeTable.getValueAt(tree.getRowForPath(editingClassPath), CLASS_OR_ELEMENT_NAME_COLUMN);

        if (!(editingPropertyObject instanceof DItemElement) || !(editingClassObject instanceof DClassElement)) {
          myTreeTable.editingStopped(e);
          return;
        }

        final DItemElement dynamicElement = (DItemElement) editingPropertyObject;
        final String name = dynamicElement.getName();
        final String className = ((DClassElement) editingClassObject).getName();

        if (dynamicElement instanceof DPropertyElement) {
          DynamicManager.getInstance(project).replaceDynamicPropertyType(className, name, (String) oldTypeValue, newTypeValue);

        } else if (dynamicElement instanceof DMethodElement) {
          final List<MyPair> myPairList = ((DMethodElement) dynamicElement).getPairs();
          DynamicManager.getInstance(project).replaceDynamicMethodType(className, name, myPairList, (String) oldTypeValue, newTypeValue);
        }
      }

      public void editingCanceled(ChangeEvent e) {
        System.out.println("editing canceled");
        myTreeTable.editingCanceled(e);
      }
    });

    RefactoringListenerManager.getInstance(project).addListenerProvider(new RefactoringElementListenerProvider() {
      @Nullable
      public RefactoringElementListener getListener(final PsiElement element) {
        if (element instanceof GrDynamicImplicitElement) {
          return new RefactoringElementListener() {
            public void elementMoved(PsiElement newElement) {
              renameElement(newElement, project, element);
            }

            public void elementRenamed(PsiElement newElement) {
              renameElement(newElement, project, element);
            }

            private void renameElement(PsiElement newElement, Project project, PsiElement element) {
              final PsiClass psiClass = ((GrDynamicImplicitElement) element).getContextElement();
              String typeText = psiClass.getQualifiedName();

              if (element instanceof GrDynamicImplicitPropertyImpl) {
                DynamicManager.getInstance(project).replaceDynamicPropertyName(typeText, ((GrDynamicImplicitElement) element).getName(), newElement.getText());
              } else if (element instanceof GrDynamicImplicitMethodImpl) {
                final PsiElement declarationScope = ((GrDynamicImplicitMethodImpl) element).getDeclarationScope();
                if (!(declarationScope instanceof GrReferenceExpression)) return;

                final PsiElement method = declarationScope.getParent();
                if (!(method instanceof GrMethodCallExpression)) return;

                final String[] types = QuickfixUtil.getMethodArgumentsTypes((GrMethodCallExpression) method);
                DynamicManager.getInstance(project).replaceDynamicMethodName(typeText, ((GrDynamicImplicitElement) element).getName(), newElement.getText(), types);
              }

            }
          };
        }
        return null;
      }
    });

    myTreeTable.setDefaultEditor(TreeTableModel.class, propertyOrClassCellEditor);
    myTreeTable.setDefaultEditor(String.class, typeCellEditor);

    myTreeTable.registerKeyboardAction(
        new ActionListener() {
          public void actionPerformed(ActionEvent event) {
            deleteRow(project);
          }
        },
        KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
        JComponent.WHEN_FOCUSED
    );

    myTreeTable.registerKeyboardAction(
        new ActionListener() {
          public void actionPerformed(ActionEvent event) {
            final int selectionRow = myTreeTable.getTree().getLeadSelectionRow();
            myTreeTable.editCellAt(selectionRow, CLASS_OR_ELEMENT_NAME_COLUMN, event);
          }
        },
        KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0),
        JComponent.WHEN_FOCUSED
    );

    myTreeTable.registerKeyboardAction(
        new ActionListener() {
          public void actionPerformed(ActionEvent event) {
            final int selectionRow = myTreeTable.getTree().getLeadSelectionRow();
            myTreeTable.editCellAt(selectionRow, TYPE_COLUMN, event);
          }
        },
        KeyStroke.getKeyStroke(KeyEvent.VK_F2, KeyEvent.CTRL_MASK),
        JComponent.WHEN_FOCUSED
    );

    // todo use "myTreeTable.setAutoCreateRowSorter(true);" since 1.6

    myTreeTable.getTree().setShowsRootHandles(true);
    myTreeTable.getTableHeader().setReorderingAllowed(false);

    myTreeTable.setPreferredScrollableViewportSize(new Dimension(300, myTreeTable.getRowHeight() * 10));
    myTreeTable.getColumn(myColumnNames[CLASS_OR_ELEMENT_NAME_COLUMN]).setPreferredWidth(200);
    myTreeTable.getColumn(myColumnNames[TYPE_COLUMN]).setPreferredWidth(160);

    JScrollPane scrollpane = ScrollPaneFactory.createScrollPane(myTreeTable);

    scrollpane.setPreferredSize(new Dimension(600, 400));
    return scrollpane;
  }

  private static void deleteRow(final Project project) {
    final int[] rows = myTreeTable.getSelectedRows();

    boolean isShowDialog = true;
    final int rowsCount = rows.length;
    int i = 0;

    final TreeTableTree tree = myTreeTable.getTree();

    for (TreePath selectionPath : tree.getSelectionPaths()) {
      if (rowsCount > 1) isShowDialog = false;
      if (i++ == 0) isShowDialog = true;

      //class
      final TreePath parent = selectionPath.getParentPath();

      if (parent.getParentPath() == null) {
        //selectionPath is class

        final Object classRow = selectionPath.getLastPathComponent();

        if (!(classRow instanceof DefaultMutableTreeNode)) return;

        if (!removeClass(project, selectionPath, ((DefaultMutableTreeNode) classRow), isShowDialog, rowsCount)) return;

      } else {
        //selectionPath is dynamic item
        final Object classRow = parent.getLastPathComponent();
        final Object dynamicRow = selectionPath.getLastPathComponent();

        if (!(classRow instanceof DefaultMutableTreeNode)) return;
        if (!(dynamicRow instanceof DefaultMutableTreeNode)) return;

        final DefaultMutableTreeNode dynamicItemNode = (DefaultMutableTreeNode) dynamicRow;
        final DefaultMutableTreeNode classNode = (DefaultMutableTreeNode) classRow;

        if (classNode.getChildCount() == 1) {
          if (!removeClass(project, selectionPath, classNode, isShowDialog, rowsCount)) return;

        } else {

          if (!removeDynamicElement(project, selectionPath, dynamicItemNode, classNode, isShowDialog, rowsCount))
            return;
        }
      }
    }
  }

  private static boolean removeClass(Project project, TreePath selectionPath, DefaultMutableTreeNode classNode, boolean isShowDialog, int rowsCount) {
    final TreeNode rootObject = classNode.getParent();
    if (!(rootObject instanceof DefaultMutableTreeNode)) return false;

    return removeDynamicElement(project, selectionPath, classNode, ((DefaultMutableTreeNode) rootObject), isShowDialog, rowsCount);
  }

  private static boolean removeDynamicElement(Project project, TreePath selectionPath, DefaultMutableTreeNode child, DefaultMutableTreeNode parent, boolean isShowDialog, int rowsCount) {
    Object namedElement = child.getUserObject();

    if (!(namedElement instanceof DNamedElement)) return false;

    if (isShowDialog) {
      int result;
      if (rowsCount > 1) {
        result = Messages.showOkCancelDialog(myBigPanel, GroovyBundle.message("are.you.sure.to.delete.elements",
            String.valueOf(rowsCount)), GroovyBundle.message("dynamic.element.deletion"),
            Messages.getQuestionIcon());

      } else {
        result = Messages.showOkCancelDialog(myBigPanel, GroovyBundle.message("are.you.sure.to.delete.dynamic.property",
            ((DNamedElement) namedElement).getName()), GroovyBundle.message("dynamic.property.deletion"),
            Messages.getQuestionIcon());
      }

      if (result != DialogWrapper.OK_EXIT_CODE) return false;
    }

    removeNamedElement(project, ((DNamedElement) namedElement));

    final Object selectionNode = selectionPath.getLastPathComponent();
    if (!(selectionNode instanceof DefaultMutableTreeNode)) return false;

    DefaultMutableTreeNode toSelect = (parent.getChildAfter(child) != null || parent.getChildCount() == 1 ?
        ((DefaultMutableTreeNode) selectionNode).getNextNode() :
        ((DefaultMutableTreeNode) selectionNode).getPreviousNode() );

//    DefaultMutableTreeNode toSelect = toSelect != null ? (DefaultMutableTreeNode) toSelect.getLastPathComponent() : null;

    removeFromParent(parent, child);
    if (toSelect != null) {
      setSelectedNode(toSelect);
    }

    return true;
  }

  private static void removeNamedElement(Project project, DNamedElement namedElement) {
    if (namedElement instanceof DClassElement) {
      DynamicManager.getInstance(project).removeClassElement(((DClassElement) namedElement).getName());
    } else if (namedElement instanceof DPropertyElement) {
      DynamicManager.getInstance(project).removePropertyElement(((DPropertyElement) namedElement));
    } else if (namedElement instanceof DMethodElement) {
      DynamicManager.getInstance(project).removeMethodElement(((DMethodElement) namedElement));
    }
  }

  public static void setSelectedNode(DefaultMutableTreeNode node) {
    JTree tree = myTreeTable.getTree();
    TreePath path = new TreePath(node.getPath());
    tree.expandPath(path.getParentPath());
    int row = tree.getRowForPath(path);
    myTreeTable.getSelectionModel().setSelectionInterval(row, row);
    myTreeTable.scrollRectToVisible(myTreeTable.getCellRect(row, 0, true));
  }


  private static void removeFromParent(DefaultMutableTreeNode parent, DefaultMutableTreeNode child) {
    int idx = myTreeTableModel.getIndexOfChild(parent, child);
    child.removeFromParent();
    myTreeTableModel.nodesWereRemoved(parent, new int[]{idx}, new TreeNode[]{child});
  }

  protected boolean isDynamicToolWindowShowing(Project project) {
    ToolWindowManager windowManager = ToolWindowManager.getInstance(project);
    ToolWindow toolWindow = windowManager.getToolWindow(DYNAMIC_TOOLWINDOW_ID);
    return toolWindow != null && toolWindow.isVisible();
  }

  private static JPanel getContentPane(Project project) {
    if (!doesTreeTableInit()) {
      buildBigPanel(project);
    }

    return myBigPanel;
  }

  private static TreeTable returnTreeTable() {
    return myTreeTable;
  }

  static class PropertyTypeColumnInfo extends ColumnInfo<DefaultMutableTreeNode, String> {
    public PropertyTypeColumnInfo(String name) {
      super(name);
    }

    public boolean isCellEditable(DefaultMutableTreeNode node) {
      final Object value = node.getUserObject();

      return !(value instanceof DClassElement);
    }

    public String valueOf(DefaultMutableTreeNode treeNode) {
      Object userObject = treeNode.getUserObject();

      if (userObject instanceof DPropertyElement)
        return QuickfixUtil.shortenType(((DPropertyElement) userObject).getType());

      if (userObject instanceof DMethodElement)
        return QuickfixUtil.shortenType(((DMethodElement) userObject).getType());

      return null;
    }
  }

  static class ClassColumnInfo extends ColumnInfo<DefaultMutableTreeNode, DNamedElement> {
    public ClassColumnInfo(String name) {
      super(name);
    }

    public boolean isCellEditable(DefaultMutableTreeNode treeNode) {
      final Object userObject = treeNode.getUserObject();

      return userObject instanceof DPropertyElement;
    }

    public Class getColumnClass() {
      return TreeTableModel.class;
    }

    public DNamedElement valueOf(DefaultMutableTreeNode treeNode) {
      Object userObject = treeNode.getUserObject();
      if (userObject instanceof DClassElement) return ((DClassElement) userObject);
      if (userObject instanceof DPropertyElement) return ((DPropertyElement) userObject);
      if (userObject instanceof DMethodElement) return ((DMethodElement) userObject);

      return null;
    }
  }

  static class DynamicFilterComponent extends FilterComponent {
    private final Project myProject;

    public DynamicFilterComponent(Project project, @NonNls String propertyName, int historySize) {
      super(propertyName, historySize);
      myProject = project;
    }

    public void filter() {
      DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
      buildTree(myProject, rootNode);

      String filterText;
      List<DefaultMutableTreeNode> classes = new ArrayList<DefaultMutableTreeNode>();
      List<DefaultMutableTreeNode> dynamicNodes = new ArrayList<DefaultMutableTreeNode>();

      DefaultMutableTreeNode classNode = (DefaultMutableTreeNode) rootNode.getFirstChild();
      while (classNode != null) {

        DefaultMutableTreeNode dynamicNode = (DefaultMutableTreeNode) classNode.getFirstChild();
        while (dynamicNode != null) {

          final Object childObject = dynamicNode.getUserObject();
          if (!(childObject instanceof DItemElement)) break;

          filterText = getFilter();
          if (filterText == null || "".equals(filterText)) {
            ((DItemElement) childObject).setHightlightedText("");

            dynamicNodes.add(dynamicNode);
            dynamicNode = (DefaultMutableTreeNode) classNode.getChildAfter(dynamicNode);
            continue;
          }

          final String name = (((DItemElement) childObject)).getName();

          if (name.contains(filterText)) {
            ((DItemElement) childObject).setHightlightedText(filterText);
            dynamicNodes.add(dynamicNode);
          }

          dynamicNode = (DefaultMutableTreeNode) classNode.getChildAfter(dynamicNode);
        }

        if (!dynamicNodes.isEmpty()) {
          classes.add(classNode);
        }

        classNode.removeAllChildren();

        for (DefaultMutableTreeNode node : dynamicNodes) {
          classNode.add(node);
        }

        dynamicNodes.clear();

        classNode = (DefaultMutableTreeNode) rootNode.getChildAfter(classNode);
      }
      rootNode.removeAllChildren();

      for (DefaultMutableTreeNode aClass : classes) {
        rootNode.add(aClass);
      }

      classes.clear();

      rebuildTreeView(myProject, rootNode, true);
    }
  }

  //TODO: return effective module
  public static Module getActiveModule(Project project) {
    //TODO
    final VirtualFile currentFile = FileEditorManagerEx.getInstanceEx(project).getCurrentFile();

    if (currentFile == null) {
      //TODO
      final Module[] modules = ModuleManager.getInstance(project).getModules();
      return modules[0];
    }

    return ProjectRootManager.getInstance(project).getFileIndex().getModuleForFile(currentFile);
  }

  public static ListTreeTableModelOnColumns getTreeTableModel(ToolWindow window, Project project) {
    if (!doesTreeTableInit()) {
      reconfigureWindow(project, window);
    }

    return returnTreeTableModel();
  }

  private static ListTreeTableModelOnColumns returnTreeTableModel() {
    return myTreeTableModel;
  }

  private static class MyColoredTreeCellRenderer extends ColoredTreeCellRenderer {
    public void customizeCellRenderer(JTree tree,
                                      Object value,
                                      boolean selected,
                                      boolean expanded,
                                      boolean leaf,
                                      int row,
                                      boolean hasFocus) {
      value = ((DefaultMutableTreeNode) value).getUserObject();

      setPaintFocusBorder(false);

      if (!(value instanceof DNamedElement)) return;

      if (value instanceof DClassElement) {
        final String containingClassName = ((DClassElement) value).getName();
        final String className = QuickfixUtil.shortenType(containingClassName);
//        append(className, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        append(containingClassName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
      }

      if (value instanceof DItemElement) {
        final DItemElement itemElement = ((DItemElement) value);
        final String substringToHighlight = itemElement.getHightlightedText();
        final String name = itemElement.getName();

        if (substringToHighlight != null) {
          appendHighlightName(substringToHighlight, name);
        } else {
          appendName(name);
        }

        if (value instanceof DMethodElement) {
          appendMethodAttributes((DMethodElement) value);
        }
      }
    }

    private void appendHighlightName(String substringToHighlight, String name) {
      final int begin = name.indexOf(substringToHighlight);
//          if (name.length() <= begin) return;
      final String first = name.substring(0, begin);
      append(first, SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES);
      final TextAttributes textAttributes = TextAttributes.ERASE_MARKER;
      textAttributes.setBackgroundColor(UIUtil.getListSelectionBackground());
      append(substringToHighlight, SimpleTextAttributes.fromTextAttributes(textAttributes));
      append(name.substring(first.length() + substringToHighlight.length()), SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES);
    }

    private void appendName(String name) {
      append(name, SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES);
    }

    private void appendMethodAttributes(DMethodElement value) {
      append("(", SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES);

      final String[] types = QuickfixUtil.getArgumentsTypes(value.getPairs());
      for (int i = 0; i < types.length; i++) {
        if (i != 0) append(", ", SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES);

        String type = types[i];
        append(QuickfixUtil.shortenType(type), SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES);
      }
      append(")", SimpleTextAttributes.SIMPLE_CELL_ATTRIBUTES);
    }
  }

  private static class MyPropertyOrClassCellEditor extends AbstractTableCellEditor {
    private final JTextField field = new JTextField();

    public MyPropertyOrClassCellEditor() {
    }

    public Object getCellEditorValue() {
      return field.getText();
    }

    public boolean isCellEditable(EventObject anEvent) {
      return false;
    }

    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
      if (value instanceof String) {
        field.setText(((String) value));
      } else if (value instanceof DClassElement) {
        field.setText(((DClassElement) value).getName());
      }

      return field;
    }
  }

  private static class MyPropertyTypeCellEditor extends AbstractTableCellEditor {
    final JTextField field = new JTextField();

    public String getCellEditorValue() {
      return field.getText();
    }

    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
      if (value instanceof String) {
        field.setText(((String) value));
      }

      return field;
    }
  }

  @Nullable
  private static TreeTableTree getTree() {
    return myTreeTable != null ? myTreeTable.getTree() : null;
  }
}