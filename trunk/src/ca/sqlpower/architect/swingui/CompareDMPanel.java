package ca.sqlpower.architect.swingui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import org.apache.log4j.Logger;

import sun.misc.Sort;

import ca.sqlpower.architect.ArchitectDataSource;
import ca.sqlpower.architect.ArchitectException;
import ca.sqlpower.architect.SQLCatalog;
import ca.sqlpower.architect.SQLColumn;
import ca.sqlpower.architect.SQLDatabase;
import ca.sqlpower.architect.SQLObject;
import ca.sqlpower.architect.SQLRelationship;
import ca.sqlpower.architect.SQLTable;
import ca.sqlpower.architect.ddl.DDLGenerator;
import ca.sqlpower.architect.ddl.DDLStatement;
import ca.sqlpower.architect.ddl.DDLUtils;
import ca.sqlpower.architect.diff.ArchitectDiffException;
import ca.sqlpower.architect.diff.CompareSQL;
import ca.sqlpower.architect.diff.DiffChunk;
import ca.sqlpower.architect.diff.DiffType;
import ca.sqlpower.architect.swingui.ASUtils.LabelValueBean;

import com.jgoodies.forms.builder.DefaultFormBuilder;
import com.jgoodies.forms.debug.FormDebugPanel;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;

public class CompareDMPanel extends JPanel {

	private static final Logger logger = Logger.getLogger(CompareDMPanel.class);

	private static final String OUTPUT_ENGLISH = "OUTPUT_ENGLISH";

	private static final String OUTPUT_SQL = "OUTPUT_SQL";

	public static final String DBCS_DIALOG_TITLE = "New Database Connection";

	private JProgressBar progressBar;

	private JPanel buttonPanel;

	private JComboBox sqlTypeDropdown;

	private JTextPane outputTextPane; // XXX: maybe unused?

	private JRadioButton sqlButton;

	private JRadioButton englishButton;

	private JLabel statusLabel;

	private StartCompareAction startCompareAction;

	private SourceOrTargetStuff source = new SourceOrTargetStuff();

	private SourceOrTargetStuff target = new SourceOrTargetStuff();

	/**
	 * Contains all of the properties and GUI components that relate to the
	 * source or target system. The idea is, the panel will have two instances
	 * of this class: One for the "source" system, and the other for the
	 * "target" system.
	 * 
	 * <p>
	 * Note: this class is only public because the test needs to refer to it. :(
	 */
	public class SourceOrTargetStuff {

		private JComboBox databaseDropdown;

		private JComboBox catalogDropdown;

		private JComboBox schemaDropdown;

		private JButton newConnButton;

		private JButton loadFileButton;

		private JTextField loadFilePath;

		/** The group for the source/target type (playpen, file, or database) */
		private ButtonGroup buttonGroup = new ButtonGroup();

		private JRadioButton playPenRadio;

		private JRadioButton physicalRadio;

		private JRadioButton loadRadio;

		private JDialog newConnectionDialog;

		/**
		 * The last database returned by getDatabase(). Never access this
		 * directly; always use getDatabase().
		 */
		private SQLDatabase cachedDatabase;

		private Action newConnectionAction = new AbstractAction("New...") {
			public void actionPerformed(ActionEvent e) {
				if (getNewConnectionDialog() != null) {
					getNewConnectionDialog().requestFocus();
					return;
				}
				final DBCSPanel dbcsPanel = new DBCSPanel();
				dbcsPanel.setDbcs(new ArchitectDataSource());
				JButton okButton = new JButton("Ok");
				okButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent e) {
						dbcsPanel.applyChanges();
						ArchitectDataSource newDS = dbcsPanel.getDbcs();
						databaseDropdown.addItem(newDS);
						databaseDropdown.setSelectedItem(newDS);
						getNewConnectionDialog().dispose();
						setNewConnectionDialog(null);
					}
				});
				JButton cancelButton = new JButton("Cancel");
				cancelButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent e) {
						dbcsPanel.discardChanges();
						getNewConnectionDialog().dispose();
						setNewConnectionDialog(null);
					}
				});

				JDialog d = ASUtils.createOkCancelDialog(dbcsPanel,
						SwingUtilities.getWindowAncestor(CompareDMPanel.this),
						DBCS_DIALOG_TITLE, okButton, cancelButton);

				setNewConnectionDialog(d);
				d.setVisible(true);
			}
		};

		private Action chooseFileAction = new AbstractAction("Choose...") {
			public void actionPerformed(ActionEvent e) {
				JFileChooser chooser = new JFileChooser();
				chooser.addChoosableFileFilter(ASUtils.ARCHITECT_FILE_FILTER);
				int returnVal = chooser.showOpenDialog(CompareDMPanel.this);
				if (returnVal == JFileChooser.APPROVE_OPTION) {
					final File file = chooser.getSelectedFile();
					loadFilePath.setText(file.getPath());
				}
			}
		};

		/**
		 * Finds all the children of a database and puts them in the GUI.
		 */
		public class CatalogPopulator extends Populator implements
				ActionListener {

			private SQLDatabase db;

			/**
			 * Checks the datasource selected in the databaseDropdown, and
			 * starts a worker thread to read its contents if it exists.
			 * 
			 * <p>
			 * Otherwise, clears out the catalog and schema dropdowns and does
			 * not start a worker thread.
			 */
			public void actionPerformed(ActionEvent e) {
				startCompareAction.setEnabled(false);
				db = getDatabase();
				if (db != null) {

					try {
						progressMonitor = db.getProgressMonitor();
					} catch (ArchitectException e1) {
						logger.debug("Error getting progressMonitor", e1);
					}

					// disable start button (listers will reenable it when
					// finished)
					if (((JComboBox) (e.getSource())).getSelectedIndex() == 0) {
						startCompareAction.setEnabled(false);
					}

					new Thread(this).start();

				} else {
					catalogDropdown.removeAllItems();
					catalogDropdown.setEnabled(false);

					schemaDropdown.removeAllItems();
					schemaDropdown.setEnabled(false);
				}
			}

			/**
			 * Populates the database <tt>db</tt> which got set up in
			 * actionPerformed().
			 */
			@Override
			public void doStuff() throws Exception {

				try {
					ListerProgressBarUpdater progressBarUpdater = new ListerProgressBarUpdater(
							progressBar, this);
					new javax.swing.Timer(100, progressBarUpdater).start();

					db.populate();

				} catch (ArchitectException e) {
					logger
							.debug(
									"Unexpected architect exception in ConnectionListener",
									e);
				}
			}

			/**
			 * Does GUI cleanup work on the Swing EDT once the worker is done.
			 * 
			 * <p>
			 * This work involves:
			 * <ul>
			 * <li>Check which child type the database has
			 * <li>Populate the catalog and schema boxes accordingly
			 * <li>Enable or disable the catalog and schema boxes accordingly
			 * </ul>
			 */
			@Override
			public void cleanup() throws ArchitectException {
				setCleanupExceptionMessage("Could not populate catalog dropdown!");

				catalogDropdown.removeAllItems();
				catalogDropdown.setEnabled(false);

				// This is either a database, a catalog, or null depending on
				// how db is structured
				SQLObject schemaParent;

				if (db.isCatalogContainer()) {
					for (SQLObject item : (List<SQLObject>) db.getChildren()) {
						catalogDropdown.addItem(item);
					}

					// check if we need to do schemas
					SQLCatalog cat = (SQLCatalog) catalogDropdown
							.getSelectedItem();
					if (cat == null) {
						// there are no catalogs (database is completely empty)
						schemaParent = null;
						catalogDropdown.setEnabled(false);
					} else if (cat.isSchemaContainer()) {
						// there are schemas in this catalog
						schemaParent = cat;
						catalogDropdown.setEnabled(true);
					} else {
						// there are catalogs, but they don't contain schemas
						schemaParent = null;
						catalogDropdown.setEnabled(true);
					}

				} else if (db.isSchemaContainer()) {
					schemaParent = db;
					catalogDropdown.setEnabled(false);
				} else {
					// database contains tables directly
					schemaParent = null;
					catalogDropdown.setEnabled(false);
				}

				schemaDropdown.removeAllItems();
				schemaDropdown.setEnabled(false);

				if (schemaParent == null) {
					startCompareAction.setEnabled(isStartable());
				} else {
					// need a final reference to this so we can use it in the
					// inner class
					final SQLObject finalSchemaParent = schemaParent;

					new Thread(new Populator() {

						@Override
						public void doStuff() throws Exception {
							ListerProgressBarUpdater progressBarUpdater = new ListerProgressBarUpdater(
									progressBar, this);
							new javax.swing.Timer(100, progressBarUpdater)
									.start();
							// this populates the schema parent (populate is not
							// visible here)
							finalSchemaParent.getChildren();
						}

						/**
						 * Populates the schema dropdown box from the schema
						 * parent that doStuff() populated.
						 * 
						 * @throws ArchitectException
						 */
						@Override
						public void cleanup() throws ArchitectException {
							setCleanupExceptionMessage("Could not populate schema dropdown!");

							for (SQLObject item : (List<SQLObject>) finalSchemaParent
									.getChildren()) {
								schemaDropdown.addItem(item);
							}

							if (schemaDropdown.getItemCount() > 0) {
								schemaDropdown.setEnabled(true);
							}

							startCompareAction.setEnabled(isStartable());
						}
					}).start();
				}
			}
		}

		/**
		 * Finds all the children of a catalog and puts them in the GUI.
		 */
		public class SchemaPopulator extends Populator implements
				ActionListener {

			/**
			 * Clears the schema dropdown, and starts a worker thread to
			 * repopulate it (if possible).
			 */
			public void actionPerformed(ActionEvent e) {
				logger.debug("SCHEMA POPULATOR IS ABOUT TO START...");
				schemaDropdown.removeAllItems();
				schemaDropdown.setEnabled(false);

				SQLCatalog catToPopulate = (SQLCatalog) catalogDropdown
						.getSelectedItem();

				if (catToPopulate != null) {
					startCompareAction.setEnabled(false);
					Thread t = new Thread(this);
					t.start();
				}
			}

			@Override
			public void doStuff() throws ArchitectException {
				logger.debug("SCHEMA POPULATOR IS STARTED...");
				ListerProgressBarUpdater progressBarUpdater = new ListerProgressBarUpdater(
						progressBar, this);
				new javax.swing.Timer(100, progressBarUpdater).start();

				SQLCatalog catToPopulate = (SQLCatalog) catalogDropdown
						.getSelectedItem();
				catToPopulate.getChildren(); // this might take a while
			}

			/**
			 * Examines the newly-populated catalog and adds its schemas to the
			 * GUI. If the catalog doesn't contain schemas, cleanup just checks
			 * if the comparison action is startable.
			 * 
			 * @throws ArchitectException
			 */
			@Override
			public void cleanup() throws ArchitectException {
				logger.debug("SCHEMA POPULATOR IS ABOUT TO CLEAN UP...");

				SQLCatalog populatedCat = (SQLCatalog) catalogDropdown
						.getSelectedItem();

				if (populatedCat.isSchemaContainer()) {
					for (SQLObject item : (List<SQLObject>) populatedCat
							.getChildren()) {
						schemaDropdown.addItem(item);
					}

					if (schemaDropdown.getItemCount() > 0) {
						schemaDropdown.setEnabled(true);
					}
				}
				startCompareAction.setEnabled(isStartable());
			}

		}

		public synchronized JDialog getNewConnectionDialog() {
			return newConnectionDialog;
		}

		private synchronized void setNewConnectionDialog(JDialog d) {
			newConnectionDialog = d;
		}

		/**
		 * Creates the GUI components associated with this object, and appends
		 * them to the given builder.
		 */
		private void buildPartialUI(DefaultFormBuilder builder,
				boolean defaultPlayPen) {

			String prefix;
			if (defaultPlayPen == true) {
				prefix = "source";
			} else {
				prefix = "target";
			}
			CellConstraints cc = new CellConstraints();

			ArchitectFrame af = ArchitectFrame.getMainInstance();
			SwingUIProject project = af.getProject();

			playPenRadio = new JRadioButton();
			playPenRadio.setName(prefix + "PlayPenRadio");
			physicalRadio = new JRadioButton();
			physicalRadio.setName(prefix + "PhysicalRadio");
			loadRadio = new JRadioButton();
			loadRadio.setName(prefix + "LoadRadio");

			buttonGroup.add(playPenRadio);
			buttonGroup.add(physicalRadio);
			buttonGroup.add(loadRadio);

			schemaDropdown = new JComboBox();
			schemaDropdown.setEnabled(false);
			schemaDropdown.setName(prefix + "SchemaDropdown");

			catalogDropdown = new JComboBox();
			catalogDropdown.setEnabled(false);
			catalogDropdown.setName(prefix + "CatalogDropdown");

			databaseDropdown = new JComboBox();
			databaseDropdown.setName(prefix + "DatabaseDropdown");
			databaseDropdown.addItem(null); // the non-selection selection
			for (ArchitectDataSource ds : af.getUserSettings().getConnections()) {
				databaseDropdown.addItem(ds);
			}
			databaseDropdown.setEnabled(false);
			databaseDropdown.setRenderer(dataSourceRenderer);

			newConnButton = new JButton();
			newConnButton.setName(prefix + "NewConnButton");
			newConnButton.setAction(newConnectionAction);
			newConnectionAction.setEnabled(false);

			loadFilePath = new JTextField();
			loadFilePath.setName(prefix + "LoadFilePath");
			loadFilePath.setEnabled(false);
			loadFilePath.getDocument().addDocumentListener(
					new DocumentListener() {
						public void insertUpdate(DocumentEvent e) {
							startCompareAction.setEnabled(isStartable());
						}

						public void removeUpdate(DocumentEvent e) {
							startCompareAction.setEnabled(isStartable());
						}

						public void changedUpdate(DocumentEvent e) {
							startCompareAction.setEnabled(isStartable());
						}
					});
			loadFileButton = new JButton();
			loadFileButton.setName(prefix + "LoadFileButton");
			loadFileButton.setAction(chooseFileAction);
			chooseFileAction.setEnabled(false);

			catalogDropdown.addActionListener(new SchemaPopulator());
			databaseDropdown.addActionListener(new CatalogPopulator());

			ActionListener listener = new OptionGroupListener();
			playPenRadio.addActionListener(listener);
			physicalRadio.addActionListener(listener);
			loadRadio.addActionListener(listener);

			if (defaultPlayPen) {
				playPenRadio.doClick();
			} else {
				physicalRadio.doClick();
			}

			// now give all our shiny new components to the builder
			builder.append(playPenRadio);
			builder.append("Current Project [" + project.getName() + "]");
			builder.nextLine();

			builder.append(""); // takes up blank space
			builder.append(physicalRadio);
			builder.append("Physical Database");
			// builder.nextColumn(2);
			builder.append("Catalog");
			builder.append("Schema");
			builder.appendRow(builder.getLineGapSpec());
			builder.appendRow("pref");
			builder.nextLine(2);
			builder.nextColumn(4);
			builder.append(databaseDropdown);
			builder.append(catalogDropdown, schemaDropdown, newConnButton);
			builder.nextLine();

			builder.append("");
			builder.append(loadRadio);
			builder.append("From File:");
			builder.nextLine();
			builder.append(""); // takes up blank space
			builder.add(loadFilePath, cc.xyw(5, builder.getRow(), 5));
			builder.nextColumn(8);
			builder.append(loadFileButton);
			builder.nextLine();

		}

		/**
		 * Figures out which SQLObject holds the tables we want to compare, and
		 * returns it.
		 * 
		 * @throws ArchitectException
		 * @throws IOException
		 * @throws IOException
		 */
		public SQLObject getObjectToCompare() throws ArchitectException,
				IOException {
			SQLObject o;
			if (playPenRadio.isSelected()) {
				o = ArchitectFrame.getMainInstance().playpen.getDatabase();
			} else if (physicalRadio.isSelected()) {
				if (schemaDropdown.getSelectedItem() != null) {
					o = (SQLObject) schemaDropdown.getSelectedItem();
				} else if (catalogDropdown.getSelectedItem() != null) {
					o = (SQLObject) catalogDropdown.getSelectedItem();
				} else if (databaseDropdown.getSelectedItem() != null) {
					o = getDatabase();
				} else {
					throw new IllegalStateException(
							"You elected to compare a physical database, "
									+ "but have not selected a "
									+ "schema, catalog, or database to compare.");
				}

			} else if (loadRadio.isSelected()) {
				SwingUIProject project = new SwingUIProject("Source");
				File f = new File(loadFilePath.getText());
				project.load(new BufferedInputStream(new FileInputStream(f)));
				o = project.getTargetDatabase();
			} else {
				throw new IllegalStateException(
						"Do not which source to compare from");
			}

			return o;
		}

		/**
		 * The public isStartable() method uses this to check source and target
		 * readiness.
		 * 
		 * XXX: this is really similar to the getObjectToCompare() method,
		 * except that it doesn't try to load the file (so it runs quicker)
		 */
		private boolean isThisPartStartable() {
			if (playPenRadio.isSelected()) {
				return true;
			} else if (physicalRadio.isSelected()) {
				return databaseDropdown.getSelectedItem() != null;
			} else if (loadRadio.isSelected()) {
				return new File(loadFilePath.getText()).canRead();
			} else {
				throw new IllegalStateException(
						"None of the radio buttons are selected!");
			}
		}

		/**
		 * Returns the currently selected database. Only creates a new
		 * SQLDatabase instance if necessary.
		 */
		public synchronized SQLDatabase getDatabase() {
			ArchitectDataSource ds = (ArchitectDataSource) databaseDropdown
					.getSelectedItem();
			if (ds == null) {
				cachedDatabase = null;
			} else if (cachedDatabase == null
					|| !cachedDatabase.getDataSource().equals(ds)) {
				cachedDatabase = new SQLDatabase(ds);
			}
			return cachedDatabase;
		}

		/**
		 * This listener is used to enable/disable JComponents when one of the
		 * database choosing options is choosen (for both source and target
		 * selections).
		 */
		public class OptionGroupListener implements ActionListener {

			public void actionPerformed(ActionEvent e) {
				enableDisablePhysicalComps();

				boolean enableLoadComps = e.getSource() == loadRadio;
				loadFilePath.setEnabled(enableLoadComps);
				loadFileButton.setEnabled(enableLoadComps);
			}
		}

		/**
		 * For the special case of enabling and disabling the Physical database
		 * Dropdown Components.
		 */
		void enableDisablePhysicalComps() {
			boolean enable = physicalRadio.isSelected();

			databaseDropdown.setEnabled(enable);

			if (enable && catalogDropdown.getItemCount() > 0) {
				catalogDropdown.setEnabled(true);
			} else {
				catalogDropdown.setEnabled(false);
			}

			if (enable && schemaDropdown.getItemCount() > 0) {
				schemaDropdown.setEnabled(true);
			} else {
				schemaDropdown.setEnabled(false);
			}

			newConnectionAction.setEnabled(enable);
		}
	}

	/**
	 * Renders list cells which have a value that is an ArchitectDataSource.
	 */
	private ListCellRenderer dataSourceRenderer = new DefaultListCellRenderer() {
		public Component getListCellRendererComponent(JList list, Object value,
				int index, boolean isSelected, boolean cellHasFocus) {
			ArchitectDataSource ds = (ArchitectDataSource) value;
			String label;
			if (ds == null) {
				label = "(Choose a Connection)";
			} else {
				label = ds.getName();
			}
			return super.getListCellRendererComponent(list, label, index,
					isSelected, cellHasFocus);
		}
	};

	/**
	 * Returns true iff the comparison process can start given the current state
	 * of the GUI form.
	 */
	public boolean isStartable() {
		logger.debug("isStartable is checking...");
		return source.isThisPartStartable() && target.isThisPartStartable();
	}

	public Action getStartCompareAction() {
		return startCompareAction;
	}

	public JPanel getButtonPanel() {
		return buttonPanel;
	}

	public CompareDMPanel() {
		buildUI();
	}

	private void buildUI() {

		progressBar = new JProgressBar();
		progressBar.setIndeterminate(true);
		progressBar.setVisible(false);

		sqlTypeDropdown = new JComboBox(DDLUtils.getDDLTypes());
		sqlTypeDropdown.setName("sqlTypeDropDown");
		OutputChoiceListener listener = new OutputChoiceListener(
				sqlTypeDropdown);
		sqlButton = new JRadioButton();
		sqlButton.setName(OUTPUT_SQL);
		sqlButton.setActionCommand(OUTPUT_SQL);
		sqlButton.setSelected(true);
		sqlButton.addActionListener(listener);

		englishButton = new JRadioButton();
		englishButton.setName("englishButton");
		englishButton.setActionCommand(OUTPUT_ENGLISH);
		englishButton.setSelected(false);
		englishButton.addActionListener(listener);

		// Group the radio buttons.
		ButtonGroup outputGroup = new ButtonGroup();
		outputGroup.add(sqlButton);
		outputGroup.add(englishButton);

		// outputDoc outputTextPane

		outputTextPane = new JTextPane();
		outputTextPane.setCaretPosition(0);
		outputTextPane.setMargin(new Insets(5, 5, 5, 5));

		JScrollPane scrollPane = new JScrollPane(outputTextPane);
		scrollPane.setPreferredSize(new Dimension(300, 300));

		startCompareAction = new StartCompareAction();
		startCompareAction.setEnabled(false);

		buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

		FormLayout formLayout = new FormLayout("20dlu, 2dlu, pref, 4dlu," + // 1-4
				"pref:grow, 4dlu, pref:grow, 4dlu," + // 5-8
				"pref:grow, 4dlu, pref", // 9-11
				"");
		formLayout.setColumnGroups(new int[][] { { 5, 7, 9, } });
		JPanel panel = logger.isDebugEnabled() ? new FormDebugPanel()
				: new JPanel();
		DefaultFormBuilder builder = new DefaultFormBuilder(formLayout, panel);
		builder.setDefaultDialogBorder();

		CellConstraints cc = new CellConstraints();

		builder.appendSeparator("Compare Source");
		builder.nextLine();
		builder.append(""); // takes up blank space

		source.buildPartialUI(builder, true);

		builder.appendSeparator("With Target");
		builder.appendRow(builder.getLineGapSpec());
		builder.appendRow("pref");
		builder.nextLine(2);
		builder.append("");

		target.buildPartialUI(builder, false);

		ActionListener radioButtonActionEnabler = new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				startCompareAction.setEnabled(isStartable());
			}
		};
		source.playPenRadio.addActionListener(radioButtonActionEnabler);
		source.physicalRadio.addActionListener(radioButtonActionEnabler);
		source.loadRadio.addActionListener(radioButtonActionEnabler);

		target.playPenRadio.addActionListener(radioButtonActionEnabler);
		target.physicalRadio.addActionListener(radioButtonActionEnabler);
		target.loadRadio.addActionListener(radioButtonActionEnabler);

		builder.appendSeparator("Output Format");
		builder.appendRow(builder.getLineGapSpec());
		builder.appendRow("pref");
		builder.nextLine(2);
		builder.nextColumn(2);
		builder.append(sqlButton);

		JPanel ddlTypePanel = new JPanel(new BorderLayout(3, 3));
		ddlTypePanel.add(new JLabel("SQL for"), BorderLayout.WEST);
		ddlTypePanel.add(sqlTypeDropdown, BorderLayout.CENTER); // ddl generator
																// type list
		builder.append(ddlTypePanel);

		builder.appendRow(builder.getLineGapSpec());
		builder.appendRow("pref");
		builder.nextLine(2);
		builder.nextColumn(2);
		builder.append(englishButton);
		builder.append("English descriptions");
		builder.nextLine();

		builder.appendSeparator("Status");
		builder.appendRow(builder.getLineGapSpec());
		builder.appendRow("pref");
		builder.nextLine(2);
		statusLabel = new JLabel("");
		builder.add(statusLabel, cc.xy(5, builder.getRow()));
		builder.add(progressBar, cc.xyw(7, builder.getRow(), 5));

		setLayout(new BorderLayout());
		add(builder.getPanel());
	}

	/**
	 * Handles disabling and enabling the "DDL Type" dropdown box.
	 */
	public static class OutputChoiceListener implements ActionListener {

		JComboBox cb;

		public OutputChoiceListener(JComboBox cb) {
			this.cb = cb;
		}

		public void actionPerformed(ActionEvent e) {
			if (e.getActionCommand().equals(OUTPUT_SQL)) {
				cb.setEnabled(true);
			} else {
				cb.setEnabled(false);
			}
		}

	}

	public abstract class Populator extends ArchitectSwingWorker implements
			Lister {

		protected SQLDatabase.PopulateProgressMonitor progressMonitor;

		public Integer getJobSize() throws ArchitectException {
			if (progressMonitor != null) {
				return progressMonitor.getJobSize();
			}
			return null;
		}

		public int getProgress() throws ArchitectException {
			if (progressMonitor != null) {
				return progressMonitor.getProgress();
			}
			return 0;
		}

		public boolean isFinished() throws ArchitectException {
			if (progressMonitor != null) {
				return progressMonitor.isFinished();
			}
			return true;
		}
	}

	public interface Lister {

		public Integer getJobSize() throws ArchitectException;

		public int getProgress() throws ArchitectException;

		public boolean isFinished() throws ArchitectException;

	}

	/**
	 * Intended to be called periodically by a Swing Timer thread. Whenever the
	 * actionPerformed method is called, it polls the lister for its job size
	 * and current progress, then updates the given progress bar with that
	 * information.
	 */
	public class ListerProgressBarUpdater implements ActionListener {
		private JProgressBar bar;

		private Lister lister;

		public ListerProgressBarUpdater(JProgressBar bar, Lister lister) {
			this.bar = bar;
			this.lister = lister;
		}

		/**
		 * Must be invoked on the Event Dispatch Thread, most likely by a Swing
		 * Timer.
		 */
		public void actionPerformed(ActionEvent evt) {

			try {
				Integer max = lister.getJobSize(); // could take noticable time
													// to calculate job size
				bar.setVisible(true);
				if (max != null) {
					bar.setMaximum(max.intValue());
					bar.setValue(lister.getProgress());
					bar.setIndeterminate(false);
				} else {
					bar.setIndeterminate(true);
				}

				if (lister.isFinished()) {
					bar.setVisible(false);
					((javax.swing.Timer) evt.getSource()).stop();
				}
			} catch (ArchitectException e) {
				logger.error("getProgress failt", e);
			}
		}
	}

	public class StartCompareAction extends AbstractAction {

		private Collection<SQLTable> sourceTables;

		private Collection<SQLTable> targetTables;

		public StartCompareAction() {
			super("Start");
		}

		public void actionPerformed(ActionEvent e) {
			
			startCompareAction.setEnabled(false);
			CompareDMFrame cf = null;

			try {

				SQLObject left = source.getObjectToCompare();
				if (left.getChildType() == SQLTable.class) {
					sourceTables = left.getChildren();
				} else {
					sourceTables = new ArrayList();
				}

				SQLObject right = target.getObjectToCompare();
				if (right.getChildType() == SQLTable.class) {
					targetTables = right.getChildren();
				} else {
					targetTables = new ArrayList();
				}

				// XXX: should do most or all of this work in a worker thread
				Map<DiffType, AttributeSet> styles = new HashMap<DiffType, AttributeSet>();
				{
					SimpleAttributeSet att = new SimpleAttributeSet();
					StyleConstants.setForeground(att, Color.green);
					styles.put(DiffType.LEFTONLY, att);

					att = new SimpleAttributeSet();
					StyleConstants.setForeground(att, Color.red);
					styles.put(DiffType.RIGHTONLY, att);

					att = new SimpleAttributeSet();
					StyleConstants.setForeground(att, Color.black);
					styles.put(DiffType.SAME, att);

					att = new SimpleAttributeSet();
					StyleConstants.setForeground(att, Color.orange);
					styles.put(DiffType.MODIFIED, att);
					
					att = new SimpleAttributeSet();
					StyleConstants.setForeground(att, Color.blue);
					styles.put(DiffType.KEY_CHANGED, att);
				}
				//Generate a list of Diff Chunks for both source and target
				CompareSQL sourceComp = new CompareSQL(sourceTables,
						targetTables);
				List<DiffChunk<SQLObject>> diff = sourceComp
						.generateTableDiffs();
				CompareSQL targetComp = new CompareSQL(targetTables,
						sourceTables);
				List<DiffChunk<SQLObject>> diff1 = targetComp
						.generateTableDiffs();
				DefaultStyledDocument sourceDoc = new DefaultStyledDocument();
				DefaultStyledDocument targetDoc = new DefaultStyledDocument();

				System.out.println(diff.toString());
				if (sqlButton.isSelected()) {
					DDLGenerator gen =(DDLGenerator)((Class)((LabelValueBean) sqlTypeDropdown.getSelectedItem()).getValue()).newInstance();
					List<DiffChunk<SQLObject>> addRelationships = new ArrayList();
					List<DiffChunk<SQLObject>> dropRelationships = new ArrayList();
					List<DiffChunk<SQLObject>> nonRelationship = new ArrayList();
					for(DiffChunk d : diff)
					{
						if ( d.getData() instanceof SQLRelationship)
						{
							if (d.getType() == DiffType.LEFTONLY)
							{
								dropRelationships.add(d);
							}
							else if (d.getType() == DiffType.RIGHTONLY)
							{
								addRelationships.add(d);
							}
						} else {
							nonRelationship.add(d);
						}
							
					}
					
					sqlScriptGenerator(styles, dropRelationships, sourceDoc,gen);
					
					sqlScriptGenerator(styles, nonRelationship, sourceDoc,gen);
					
					sqlScriptGenerator(styles, addRelationships, sourceDoc,gen);
					
					
					//throw new UnsupportedOperationException(
							//"We don't support DDL generation yet");
				} else if (englishButton.isSelected()) {									
					generateEnglishDescription(styles, diff, sourceDoc);
					generateEnglishDescription(styles, diff1, targetDoc);					
				} else {
					throw new IllegalStateException(
							"Don't know what type of output to make");
				}

				// get the title string for the compareDMFrame
				String compMethod = null;
				if (sqlButton.isSelected()) {
					compMethod = "SQL";
				} else {
					compMethod = OUTPUT_ENGLISH;
				}
				String titleString = "Comparing " + left.getName() + " to "
						+ right.getName() + " using " + compMethod;
				cf = new CompareDMFrame(sourceDoc, targetDoc, titleString,
						source.getDatabase(), sqlButton.isSelected());

				cf.pack();
				cf.setVisible(true);

			} catch (ArchitectDiffException ex) {
				JOptionPane.showMessageDialog(CompareDMPanel.this,
						"Could not perform the diff:\n" + ex.getMessage(),
						"Diff Error", JOptionPane.ERROR_MESSAGE);
				logger.error("Couldn't do diff", ex);
			} catch (ArchitectException exp) {
				ASUtils.showExceptionDialog("StartCompareAction failed", exp);
				logger.error("StartCompareAction failed", exp);
			} catch (FileNotFoundException ex) {
				ASUtils
						.showExceptionDialog("Your file could not be found.",
								ex);
				logger.error("File could not be found.", ex);
			} catch (IOException ex) {
				ASUtils.showExceptionDialog("Could not read file", ex);
				logger.error("Could not read file", ex);
			} catch (BadLocationException ex) {
				ASUtils.showExceptionDialog(
						"Could not create document for results", ex);
				logger.error("Could not create document for results", ex);
			} catch (Exception ex) {
				ASUtils.showExceptionDialog("Unxepected Exception!", ex);
				logger.error("Unxepected Exception!", ex);
			} finally {
				this.setEnabled(isStartable());
			}
		}
		
		/**
		 * This method generates english descriptions by taking in the diff list
		 * and putting the appropiate statements in the document.  It will iterate
		 * through the diff list and identify which type of DiffChunk it is and 
		 * what kind of SQLType it is to produce the proper english description 
		 * output
		 * @throws BadLocationException
		 * @throws ArchitectException
		 */
		private void generateEnglishDescription(
				Map<DiffType, AttributeSet> styles,
				List<DiffChunk<SQLObject>> diff, DefaultStyledDocument sourceDoc)
				throws BadLocationException, ArchitectException {
			
			//To know where to put line spaces
			int objectCount = 0;
			
			for (DiffChunk<SQLObject> chunk : diff) {
				if (chunk.getData() instanceof SQLRelationship) {
					if (objectCount > 0)
						sourceDoc.insertString(sourceDoc.getLength(), "\n",
								styles.get(chunk.getType()));

					SQLRelationship rel = (SQLRelationship) chunk.getData();
					sourceDoc.insertString(sourceDoc.getLength(), chunk
							.getData().toString()
							+ "\n  "
							+ rel.getFkTable().getName()
							+ "("
							+ rel.printKeyColumns(rel.FKCOLUMN)
							+ ")\n  refers to "
							+ rel.getPkTable().getName()
							+ "(" + rel.printKeyColumns(rel.PKCOLUMN) + ")",
							styles.get(chunk.getType()));
				} else if (chunk.getData() instanceof SQLColumn) {
					sourceDoc.insertString(sourceDoc.getLength(), "  "
							+ chunk.getData().toString(), styles.get(chunk
							.getType()));
				} else if (chunk.getData() instanceof SQLTable) {

					if (chunk.getType() == DiffType.LEFTONLY
							|| chunk.getType() == DiffType.RIGHTONLY) {

						if (objectCount > 0)
							sourceDoc.insertString(sourceDoc.getLength(), "\n",
									styles.get(chunk.getType()));

						sourceDoc.insertString(sourceDoc.getLength(), chunk
								.getData().toString(), styles.get(chunk
								.getType()));

						for (SQLColumn c : ((SQLTable) chunk.getData())
								.getColumns()) {
							sourceDoc.insertString(sourceDoc.getLength(), "\n",
									styles.get(chunk.getType()));
							sourceDoc
									.insertString(sourceDoc.getLength(), "  "
											+ c.toString(), styles.get(chunk
											.getType()));
						}
					} else if (chunk.getType() == DiffType.KEY_CHANGED) {
						SQLTable t = (SQLTable) chunk.getData();
						int count = 0;
						for (SQLColumn c : t.getColumns()) {
							if (c.isPrimaryKey()) {
								if (count++ == 0) {
									sourceDoc.insertString(sourceDoc
											.getLength(), "\n  Primary Key "
											+ t.getPrimaryKeyName() + " ("
											+ c.getPhysicalName(), styles
											.get(chunk.getType()));
								} else {
									sourceDoc.insertString(sourceDoc
											.getLength(), ","
											+ c.getPhysicalName(), styles
											.get(chunk.getType()));
								}
							}
						}
						if (count > 0) {
							sourceDoc.insertString(sourceDoc.getLength(), ")",
									styles.get(chunk.getType()));
						} else {
							sourceDoc.insertString(sourceDoc.getLength(),
									"\n  Primary Key " + t.getPrimaryKeyName()
											+ " Removed", styles.get(chunk
											.getType()));
						}

					} else {
						if (objectCount > 0)
							sourceDoc.insertString(sourceDoc.getLength(), "\n",
									styles.get(chunk.getType()));

						sourceDoc.insertString(sourceDoc.getLength(), chunk
								.getData().toString(), styles.get(chunk
								.getType()));
					}
				}
				sourceDoc.insertString(sourceDoc.getLength(), "\n", styles
						.get(chunk.getType()));
				objectCount++;
			}
		}

		private void sqlScriptGenerator(Map<DiffType, AttributeSet> styles, List<DiffChunk<SQLObject>> diff, DefaultStyledDocument targetDoc, DDLGenerator gen) throws ArchitectDiffException, SQLException, ArchitectException, BadLocationException, InstantiationException, IllegalAccessException {
			gen = gen.getClass().newInstance();
			for (DiffChunk<SQLObject> chunk : diff) {
				if (chunk.getType() == DiffType.LEFTONLY)
				{
					if (chunk.getData() instanceof SQLTable)
					{
						SQLTable t = (SQLTable) chunk.getData();
						gen.dropTable(t);
					}else if (chunk.getData() instanceof SQLColumn){
						SQLColumn c = (SQLColumn) chunk.getData();
						gen.dropColumn(c,c.getParentTable());
					} else if (chunk.getData() instanceof SQLRelationship){
						SQLRelationship r = (SQLRelationship)chunk.getData();
						gen.dropRelationship(r);
					} else {
						throw new IllegalStateException("DiffChunk is an unexpected type.");
					}
					
				} 
				else if (chunk.getType() == DiffType.RIGHTONLY)
				{
					if (chunk.getData() instanceof SQLTable)
					{
						SQLTable t = (SQLTable) chunk.getData();
						gen.writeTable(t);
					}else if (chunk.getData() instanceof SQLColumn){
						SQLColumn c = (SQLColumn) chunk.getData();
						gen.addColumn(c,c.getParentTable());
					}else if (chunk.getData() instanceof SQLRelationship){
						SQLRelationship r = (SQLRelationship)chunk.getData();
						gen.addRelationship(r);
					}else {
						throw new IllegalStateException("DiffChunk is an unexpected type.");
					}
				} 
				else if (chunk.getType() == DiffType.MODIFIED) 
				{
					if (chunk.getData() instanceof SQLColumn)
					{
						SQLColumn c = (SQLColumn) chunk.getData();
						gen.modifyColumn(c);
					} else {
						throw new IllegalStateException("DiffChunk is an unexpected type.");
					}
				} 
				else if (chunk.getType() == DiffType.KEY_CHANGED){
					//TODO:addSQLScript statement for KEY_CHANGED
					System.out.println("Program recognize KEY_CHANGED");
				}
				else {
					
				}
				// TODO add Modify columns
			}
			for ( DDLStatement statement: gen.getDdlStatements())
			{
				targetDoc.insertString(targetDoc.getLength(),statement.getSQLText()+";\n",styles.get(DiffType.SAME));
			}
		}
	}

	public SourceOrTargetStuff getSourceStuff() {
		return source;
	}

	public SourceOrTargetStuff getTargetStuff() {
		return target;
	}

	/**
	 * Just for testing the form layout without running the whole Architect.
	 * 
	 * <p>
	 * The frame it makes is EXIT_ON_CLOSE, so you should never use this in a
	 * real app.
	 */
	public static void main(String[] args) {
		final JFrame f = new JFrame("Testing compare dm panel");
		f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				f.add(new CompareDMPanel());
				f.pack();
				f.setVisible(true);
			};
		});
	}
}
