/*
 * Copyright (c) 2007, SQL Power Group Inc.
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in
 *       the documentation and/or other materials provided with the
 *       distribution.
 *     * Neither the name of SQL Power Group Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package ca.sqlpower.architect.swingui;

import java.awt.event.ActionEvent;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

import ca.sqlpower.architect.ArchitectException;
import ca.sqlpower.architect.ArchitectRuntimeException;
import ca.sqlpower.architect.SQLColumn;
import ca.sqlpower.architect.SQLIndex;
import ca.sqlpower.architect.SQLTable;
import ca.sqlpower.architect.SQLIndex.Column;
import ca.sqlpower.architect.SQLIndex.IndexType;
import ca.sqlpower.architect.SQLTable.Folder;
import ca.sqlpower.architect.undo.UndoCompoundEvent;
import ca.sqlpower.architect.undo.UndoCompoundEventListener;
import ca.sqlpower.architect.undo.UndoCompoundEvent.EventTypes;
import ca.sqlpower.swingui.DataEntryPanel;
import ca.sqlpower.swingui.table.EditableJTable;

import com.jgoodies.forms.builder.ButtonBarBuilder;
import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;

public class IndexEditPanel extends JPanel implements DataEntryPanel {
    protected SQLIndex index;
    protected SQLTable parent;
    protected SQLIndex indexCopy;
    JTextField name;
    JCheckBox unique;
    JComboBox indexType;
    JTextField qualifier;
    JTextField filterCondition;
    EditableJTable columnsList;
    
    public IndexEditPanel(SQLIndex index, ArchitectSwingSession session) throws ArchitectException{
        super(new FormLayout("pref,4dlu,pref,4dlu,pref:grow,4dlu,pref","pref,4dlu,pref,4dlu,pref,4dlu,pref,4dlu,pref,4dlu,pref:grow,4dlu,pref,4dlu"));
        createGUI(index, index.getParentTable(), session);
    }
    
    public IndexEditPanel(SQLIndex index, SQLTable parent, ArchitectSwingSession session) throws ArchitectException {
        super(new FormLayout("pref,4dlu,pref,4dlu,pref:grow,4dlu,pref","pref,4dlu,pref,4dlu,pref,4dlu,pref,4dlu,pref,4dlu,pref:grow,4dlu,pref,4dlu"));
        createGUI(index, parent, session);
    }

    private void createGUI(SQLIndex index, SQLTable parent, ArchitectSwingSession session) throws ArchitectException {
        this.parent = parent;
        addUndoEventListener(session.getUndoManager().getEventAdapter());
        PanelBuilder pb = new PanelBuilder((FormLayout)this.getLayout(),this);
        CellConstraints cc = new CellConstraints();
        pb.add(new JLabel("Index Name"),cc.xy(1, 1));
        pb.add(name = new JTextField("", 30),cc.xyw(3,1,4));
        pb.add(new JLabel("Unique"),cc.xy(1,3));
        unique = new JCheckBox();
        pb.add(unique,cc.xy(3, 3));
        pb.add(new JLabel("Index Type"),cc.xy(1,5));
        indexType = new JComboBox(IndexType.values());
        pb.add(indexType,cc.xyw(3, 5, 4));
        pb.add(new JLabel("Qualifier"),cc.xy(1,7));
        qualifier = new JTextField();
        pb.add(qualifier,cc.xyw(3, 7, 4));
        pb.add(new JLabel("Filter Condition"),cc.xy(1,9));
        filterCondition = new JTextField();
        pb.add(filterCondition,cc.xyw(3, 9, 4));
        
        editIndex(index);
        columnsList = new EditableJTable();
        columnsList.setModel(new IndexColumnTableModel(indexCopy,parent));
        columnsList.setDefaultEditor(SQLColumn.class,new IndexColumnTableCellEditor());
        columnsList.setAutoResizeMode(EditableJTable.AUTO_RESIZE_NEXT_COLUMN);
        
        pb.add(new JScrollPane(columnsList),cc.xyw(1,11,6));
        
        ButtonBarBuilder bb = new ButtonBarBuilder();
        bb.addGridded(new JButton(new AbstractAction("Add"){

            public void actionPerformed(ActionEvent e) {
                try {
                    indexCopy.addChild(indexCopy.new Column("New",false,false));
                } catch (ArchitectException e1) {
                    throw new ArchitectRuntimeException(e1);
                }
            }
            
        }));
        bb.addGridded(new JButton(new AbstractAction("Delete"){
            public void actionPerformed(ActionEvent e) {
                indexCopy.removeChild(columnsList.getSelectedRow());
            }
        }));
        pb.add(bb.getPanel(),cc.xyw(1, 13, 6));
        loadIndexIntoPanel();
    }

    public void editIndex(SQLIndex index) throws ArchitectException {
        this.index = index;
        name.setText(index.getName());
        indexCopy = new SQLIndex(index);
        indexCopy.setPrimaryKeyIndex(false);
    }
    
    private void loadIndexIntoPanel(){
        name.setText(index.getName());
        unique.setSelected(index.isUnique());
        qualifier.setText(index.getQualifier());
        filterCondition.setText(index.getFilterCondition());
        indexType.setSelectedItem(index.getType());
        name.selectAll();
    }
    
    protected SQLIndex getIndexCopy(){
        return indexCopy;
    }

    // --------------------- ArchitectPanel interface ------------------
    /**
     * Applies the changes to the panel if the data is valid
     * 
     * the data is valid if it has a name, an index type and all
     * of the columns contain primary keys
     * 
     * returns true if saved, false otherwise
     */
    public boolean applyChanges() {
        startCompoundEdit("Index Properties Change");       
        try {   
            StringBuffer warnings = new StringBuffer();
            //We need to check if the index name and/or primary key name is empty or not
            //if they are, we need to warn the user since it will mess up the SQLScripts we create
            if (name.getText().trim().length() == 0) {
                warnings.append("The index cannot be assigned a blank name \n");
                
            }
            if (index.isPrimaryKeyIndex()) {
                for (Column c:(List<Column>) indexCopy.getChildren()){
                    if (c.getColumn() == null){
                        warnings.append("Can only add columns to the primary key\n");
                        break;
                    }
                }
            }
            
            if (indexType.getSelectedItem() == null) {
                warnings.append("An index type must be selected\n");
            }
            
            if (warnings.toString().length() == 0){
                //The operation is successful
                index.makeColumnsLike(indexCopy);
                SQLTable parentTable = parent;
                if (index.isPrimaryKeyIndex()) {
                    try {
                        parentTable.setMagicEnabled(false);
                        for (SQLColumn c: parentTable.getColumns()){
                            c.setPrimaryKeySeq(null);
                        }
                        int i=0;
                        for (Column c:(List<Column>) indexCopy.getChildren()){
                            SQLColumn column = c.getColumn();
                            if (column != null){
                                column.setPrimaryKeySeq(Integer.MAX_VALUE);
                                parentTable.removeColumn(column);
                                parentTable.addColumn(i,column);
                                i++;
                            }
                        }
                    } finally {
                        parentTable.setMagicEnabled(true);
                    }
                }
                index.setName(name.getText());
                index.setUnique(unique.isSelected());
                index.setType((IndexType) indexType.getSelectedItem());
                index.setQualifier(qualifier.getText());
                index.setFilterCondition(filterCondition.getText());
                Folder<SQLIndex> indicesFolder = parentTable.getIndicesFolder();
                List children = indicesFolder.getChildren();
                if (!children.contains(index)) {
                    indicesFolder.addChild(index);
                }
                return true;
            } else{
                JOptionPane.showMessageDialog(this,warnings.toString());
                //this is done so we can go back to this dialog after the error message
                return false;
            }            
        } catch (ArchitectException e) {
            throw new ArchitectRuntimeException(e);
        } finally {
            endCompoundEdit("Ending new compound edit event in index edit panel");
        }
    }

    public void discardChanges() {
    }
    
    /**
     * The list of SQLObject property change event listeners
     * used for undo
     */
    protected LinkedList<UndoCompoundEventListener> undoEventListeners = new LinkedList<UndoCompoundEventListener>();

    
    public void addUndoEventListener(UndoCompoundEventListener l) {
        undoEventListeners.add(l);
    }

    public void removeUndoEventListener(UndoCompoundEventListener l) {
        undoEventListeners.remove(l);
    }
    
    protected void fireUndoCompoundEvent(UndoCompoundEvent e) {
        Iterator it = undoEventListeners.iterator();
        
        if (e.getType().isStartEvent()) {
            while (it.hasNext()) {
                ((UndoCompoundEventListener) it.next()).compoundEditStart(e);
            }
        } else {
            while (it.hasNext()) {
                ((UndoCompoundEventListener) it.next()).compoundEditEnd(e);
            }
        } 
    }

    public void startCompoundEdit(String message){
        fireUndoCompoundEvent(new UndoCompoundEvent(EventTypes.COMPOUND_EDIT_START,message));
    }
    
    public void endCompoundEdit(String message){
        fireUndoCompoundEvent(new UndoCompoundEvent(EventTypes.COMPOUND_EDIT_END,message));
    }
    
    public JPanel getPanel() {
        return this;
    }

    public String getNameText() {
        return name.getText();
    }

    public void setNameText(String newName) {
        name.setText(newName);
    }

    public boolean hasUnsavedChanges() {
        // TODO return whether this panel has been changed
        return false;
    }
}
