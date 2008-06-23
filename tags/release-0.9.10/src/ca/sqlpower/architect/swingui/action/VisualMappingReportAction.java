/*
 * Copyright (c) 2008, SQL Power Group Inc.
 *
 * This file is part of Power*Architect.
 *
 * Power*Architect is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Power*Architect is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */
package ca.sqlpower.architect.swingui.action;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.apache.log4j.Logger;

import ca.sqlpower.architect.ArchitectException;
import ca.sqlpower.architect.ArchitectRuntimeException;
import ca.sqlpower.architect.SQLTable;
import ca.sqlpower.architect.etl.ExportCSV;
import ca.sqlpower.architect.swingui.ASUtils;
import ca.sqlpower.architect.swingui.ArchitectSwingSession;
import ca.sqlpower.architect.swingui.MappingReport;
import ca.sqlpower.architect.swingui.TablePane;

import com.jgoodies.forms.builder.ButtonBarBuilder;

/**
 * Creates a visual report of the source columns associated with each
 * "target" column in the play pen.
 */
public class VisualMappingReportAction extends AbstractArchitectAction {

    private static final Logger logger = Logger.getLogger(VisualMappingReportAction.class);
    
    /**
     * The play pen that this action operates on.
     */
    private final ArchitectSwingSession session;
    
    /**
     * The frame that will own the dialog(s) created by this action.
     * Neither argument is allowed to be null.
     */
    private final JFrame parentFrame;
    
    public VisualMappingReportAction(JFrame parentFrame, ArchitectSwingSession session) {
        super(session, "Visual Mapping Report", "Visual Mapping Report");
        this.session = session;

        if (parentFrame == null) throw new NullPointerException("Null parentFrame");
        this.parentFrame = parentFrame;
    }

    // TODO convert this to an architect pane
    public void actionPerformed(ActionEvent e) {
        try {
            final MappingReport mr ;
            final List<SQLTable> selectedTables;
            if (playpen.getSelectedTables().size() == 0) {
                selectedTables = new ArrayList(playpen.getTables());
            } else {
                if (JOptionPane.YES_OPTION == JOptionPane.showConfirmDialog(
                        parentFrame,
                        "View only the " + playpen.getSelectedTables().size() + " selected tables",
                        "Show Mapping",
                        JOptionPane.YES_NO_OPTION)) {
                    selectedTables = new ArrayList<SQLTable>();
                    for(TablePane tp: playpen.getSelectedTables()) {
                        selectedTables.add(tp.getModel());
                    }
                } else {
                    selectedTables = new ArrayList(playpen.getTables());
                }
            }
            mr = new MappingReport(session, selectedTables);

            final JFrame f = new JFrame("Mapping Report");
            f.setIconImage(ASUtils.getFrameIconImage());

            // You call this a radar?? -- No sir, we call it Mr. Panel.
            JPanel mrPanel = new JPanel() {
                protected void paintComponent(java.awt.Graphics g) {

                    super.paintComponent(g);
                    try {
                        mr.drawHighLevelReport((Graphics2D) g,null);
                    } catch (ArchitectException e1) {
                        logger.error("ArchitectException while generating mapping diagram", e1);
                        ASUtils.showExceptionDialogNoReport(
                                "Couldn't generate mapping diagram", e1);
                    }
                }
            };
            mrPanel.setDoubleBuffered(true);
            mrPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            mrPanel.setPreferredSize(mr.getRequiredSize());
            mrPanel.setOpaque(true);
            mrPanel.setBackground(Color.WHITE);
            ButtonBarBuilder buttonBar = new ButtonBarBuilder();
            JButton csv = new JButton(new AbstractAction(){

                public void actionPerformed(ActionEvent e) {
                    FileWriter output = null;
                    try {
                        ExportCSV export = new ExportCSV(selectedTables);

                        File file = null;

                        JFileChooser fileDialog = new JFileChooser(session.getRecentMenu().getMostRecentFile());
                        fileDialog.setSelectedFile(new File("map.csv"));

                        if (fileDialog.showSaveDialog(f) == JFileChooser.APPROVE_OPTION){
                            file = fileDialog.getSelectedFile();
                        } else {
                            return;
                        }

                        output = new FileWriter(file);
                        output.write(export.getCSVMapping());
                        output.flush();
                    } catch (IOException e1) {
                        throw new RuntimeException(e1);
                    } catch (ArchitectException e1) {
                        throw new ArchitectRuntimeException(e1);
                    } finally {
                        if (output != null) {
                            try {
                                output.close();
                            } catch (IOException e1) {
                                logger.error("IO Error", e1);
                            }
                        }
                    }
                }

            });
            csv.setText("Export CSV");
            buttonBar.addGriddedGrowing(csv);
            JButton close = new JButton(new AbstractAction(){

                public void actionPerformed(ActionEvent e) {
                    f.dispose();
                }

            });
            close.setText("Close");
            buttonBar.addRelatedGap();
            buttonBar.addGriddedGrowing(close);
            JPanel basePane = new JPanel(new BorderLayout(5,5));
            basePane.add(new JScrollPane(mrPanel),BorderLayout.CENTER);
            basePane.add(buttonBar.getPanel(),BorderLayout.SOUTH);
            f.setContentPane(basePane);
            f.pack();
            f.setLocationRelativeTo(parentFrame);
            f.setVisible(true);
        } catch (ArchitectException e1) {
            throw new ArchitectRuntimeException(e1);
        }
    }
}