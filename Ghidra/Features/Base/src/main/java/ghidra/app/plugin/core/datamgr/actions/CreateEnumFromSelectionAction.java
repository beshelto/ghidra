/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.datamgr.actions;

import java.util.Arrays;
import java.util.List;

import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

import docking.ActionContext;
import docking.action.DockingAction;
import docking.action.MenuData;
import docking.widgets.dialogs.InputDialog;
import docking.widgets.tree.GTree;
import docking.widgets.tree.GTreeNode;
import ghidra.app.plugin.core.datamgr.DataTypeManagerPlugin;
import ghidra.app.plugin.core.datamgr.DataTypesActionContext;
import ghidra.app.plugin.core.datamgr.tree.DataTypeNode;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.database.data.ProgramDataTypeManager;
import ghidra.program.model.data.*;
import ghidra.program.model.data.Enum;
import ghidra.util.HelpLocation;
import ghidra.util.Msg;

public class CreateEnumFromSelectionAction extends DockingAction {
	private final DataTypeManagerPlugin plugin;

	public CreateEnumFromSelectionAction(DataTypeManagerPlugin plugin) {
		super("Enum from Selection", plugin.getName());
		this.plugin = plugin;

		setPopupMenuData(new MenuData(new String[] { "Create Enum From Selection" }, null, "Edit"));
		setHelpLocation(new HelpLocation("DataTypeManagerPlugin", "CreateEnumFromSelection"));
		setEnabled(true);
	}

	@Override
	public boolean isAddToPopup(ActionContext context) {
		if (!(context instanceof DataTypesActionContext)) {
			return false;
		}

		GTree gtree = (GTree) context.getContextObject();
		TreePath[] selectionPaths = gtree.getSelectionPaths();
		if (selectionPaths == null || selectionPaths.length <= 1) {
			return false;
		}

		return !containsInvalidNodes(selectionPaths);
	}

	@Override
	public void actionPerformed(ActionContext context) {

		//Get the selected enums 
		final GTree gTree = (GTree) context.getContextObject();
		TreePath[] paths = gTree.getSelectionPaths();
		Enum[] enumArray = new Enum[paths.length];
		int i = 0;

		for (TreePath element : paths) {
			GTreeNode node = (GTreeNode) element.getLastPathComponent();
			DataTypeNode dataTypeNode = (DataTypeNode) node;
			enumArray[i++] = (Enum) dataTypeNode.getDataType();
		}
		Category category = null;
		DataTypeManager[] dataTypeManagers = plugin.getDataTypeManagers();
		DataTypeManager myDataTypeManager = null;
		for (DataTypeManager dataTypeManager : dataTypeManagers) {
			if (dataTypeManager instanceof ProgramDataTypeManager) {
				myDataTypeManager = dataTypeManager;
				category = myDataTypeManager.getCategory(CategoryPath.ROOT);
				if (category == null) {
					Msg.error(this, "Could not find program data type manager");
					return;
				}

			}
		}

		String newName = "";
		PluginTool tool = plugin.getTool();

		while (newName.equals("")) {
			InputDialog inputDialog =
				new InputDialog("Name new ENUM", "Please enter a name for the new ENUM: ");
			tool = plugin.getTool();
			tool.showDialog(inputDialog);

			if (inputDialog.isCanceled()) {
				return;
			}
			newName = inputDialog.getValue();
		}

		DataType dt = myDataTypeManager.getDataType(category.getCategoryPath(), newName);
		while (dt != null) {
			InputDialog dupInputDialog = new InputDialog("Duplicate ENUM Name",
				"Please enter a unique name for the new ENUM: ");
			tool = plugin.getTool();
			tool.showDialog(dupInputDialog);

			if (dupInputDialog.isCanceled()) {
				return;
			}
			newName = dupInputDialog.getValue();
			dt = myDataTypeManager.getDataType(category.getCategoryPath(), newName);
		}
		createMergedEnum(category, enumArray, newName);

		// select new node in tree.  Must use invoke later to give the tree a chance to add the
		// the new node to the tree.
		myDataTypeManager.flushEvents();
		final String parentNodeName = myDataTypeManager.getName();
		final String newNodeName = newName;
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				GTreeNode rootNode = gTree.getViewRoot();
				gTree.setSelectedNodeByNamePath(
					new String[] { rootNode.getName(), parentNodeName, newNodeName });
			}
		});
	}

	@Override
	public boolean isEnabledForContext(ActionContext context) {
		if (!(context instanceof DataTypesActionContext)) {
			return false;
		}

		GTree gtree = (GTree) context.getContextObject();
		TreePath[] selectionPaths = gtree.getSelectionPaths();
		if (selectionPaths == null || selectionPaths.length <= 1) {
			return false;
		}

		return !containsInvalidNodes(selectionPaths);
	}

	private boolean containsInvalidNodes(TreePath[] selectionPaths) {

		// determine if all selected nodes are ENUMs, if so, return true, if not, return false
		for (TreePath path : selectionPaths) {
			GTreeNode node = (GTreeNode) path.getLastPathComponent();
			if (!(node instanceof DataTypeNode)) {
				return true;
			}
			DataTypeNode dataTypeNode = (DataTypeNode) node;
			DataType dataType = dataTypeNode.getDataType();
			if (!(dataType instanceof Enum)) {
				return true;
			}
		}
		return false;
	}

	private void createMergedEnum(Category category, Enum[] enumsToMerge, String mergedEnumName) {

		int maxEnumSize = computeNewEnumSize(enumsToMerge);

		SourceArchive sourceArchive = category.getDataTypeManager().getLocalSourceArchive();
		Enum mergedEnum = new EnumDataType(category.getCategoryPath(), mergedEnumName, maxEnumSize,
			category.getDataTypeManager());

		mergedEnum.setSourceArchive(sourceArchive);

		for (Enum element : enumsToMerge) {
			mergeEnum(mergedEnum, element);
		}

		addEnumDataType(category, mergedEnum);

	}

	private void addEnumDataType(Category category, Enum mergedEnum) {
		int id = category.getDataTypeManager().startTransaction("Create New Enum Data Type");
		category.getDataTypeManager()
				.addDataType(mergedEnum, DataTypeConflictHandler.REPLACE_HANDLER);
		category.getDataTypeManager().endTransaction(id, true);
	}

	private void mergeEnum(Enum mergedEnum, Enum enumToMerge) {

		for (String name : enumToMerge.getNames()) {

			long valueToAdd = enumToMerge.getValue(name);
			String comment = "";

			if (isDuplicateEntry(mergedEnum, enumToMerge, name)) {
				continue;
			}

			if (isConflictingEntry(mergedEnum, enumToMerge, name)) {
				name = createDeconflictedName(mergedEnum, name);

				comment = "NOTE: Duplicate name with different value";
				Msg.debug(this,
					"Merged Enum " + mergedEnum.getName() +
						" has at least one duplicate named entry with different value than " +
						"original. Underscore(s) have been appended to name allow addition.");
			}

			mergedEnum.add(name, valueToAdd, comment);

		}
	}

	private String createDeconflictedName(Enum enumm, String name) {

		List<String> existingNames = Arrays.asList(enumm.getNames());
		while (existingNames.contains(name)) {
			name = name + "_";
		}
		return name;
	}

	private boolean isDuplicateEntry(Enum mergedEnum, Enum enumToMerge, String name) {

		List<String> existingNames = Arrays.asList(mergedEnum.getNames());

		if (!existingNames.contains(name)) {
			return false;
		}

		long valueToAdd = enumToMerge.getValue(name);
		long existingValue = mergedEnum.getValue(name);
		if (valueToAdd == existingValue) {
			return true;
		}

		return false;
	}

	private boolean isConflictingEntry(Enum mergedEnum, Enum enumToMerge, String name) {

		List<String> existingNames = Arrays.asList(mergedEnum.getNames());

		if (!existingNames.contains(name)) {
			return false;
		}

		long valueToAdd = enumToMerge.getValue(name);
		long existingValue = mergedEnum.getValue(name);
		if (valueToAdd == existingValue) {
			return false;
		}

		return true;
	}

	// figure out size in bytes of the new enum using the max size of the selected enums
	private int computeNewEnumSize(Enum[] enumArray) {
		int maxEnumSize = 1;
		for (Enum element : enumArray) {
			if (maxEnumSize < element.getLength()) {
				maxEnumSize = element.getLength();
			}
		}
		return maxEnumSize;
	}

}
