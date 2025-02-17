/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2019 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.editors.sql;


import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.*;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.*;
import org.eclipse.jface.text.rules.FastPartitioner;
import org.eclipse.jface.text.rules.IToken;
import org.eclipse.jface.text.source.*;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionSupport;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.*;
import org.eclipse.ui.texteditor.templates.ITemplatesPage;
import org.eclipse.ui.themes.IThemeManager;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.*;
import org.jkiss.dbeaver.model.sql.completion.SQLCompletionContext;
import org.jkiss.dbeaver.model.sql.parser.SQLParserPartitions;
import org.jkiss.dbeaver.model.sql.parser.SQLWordDetector;
import org.jkiss.dbeaver.model.sql.registry.SQLCommandsRegistry;
import org.jkiss.dbeaver.model.text.TextUtils;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.*;
import org.jkiss.dbeaver.ui.editors.BaseTextEditorCommands;
import org.jkiss.dbeaver.ui.editors.EditorUtils;
import org.jkiss.dbeaver.ui.editors.sql.internal.SQLEditorMessages;
import org.jkiss.dbeaver.ui.editors.sql.preferences.*;
import org.jkiss.dbeaver.ui.editors.sql.syntax.SQLCharacterPairMatcher;
import org.jkiss.dbeaver.ui.editors.sql.syntax.SQLEditorCompletionContext;
import org.jkiss.dbeaver.ui.editors.sql.syntax.SQLPartitionScanner;
import org.jkiss.dbeaver.ui.editors.sql.syntax.SQLRuleManager;
import org.jkiss.dbeaver.ui.editors.sql.syntax.tokens.SQLControlToken;
import org.jkiss.dbeaver.ui.editors.sql.syntax.tokens.SQLToken;
import org.jkiss.dbeaver.ui.editors.sql.templates.SQLTemplatesPage;
import org.jkiss.dbeaver.ui.editors.sql.util.SQLSymbolInserter;
import org.jkiss.dbeaver.ui.editors.text.BaseTextEditor;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.Pair;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;

/**
 * SQL Executor
 */
public abstract class SQLEditorBase extends BaseTextEditor implements DBPContextProvider, IErrorVisualizer {

    static protected final Log log = Log.getLog(SQLEditorBase.class);
    private static final long MAX_FILE_LENGTH_FOR_RULES = 2000000;

    public static final String STATS_CATEGORY_SELECTION_STATE = "SelectionState";

    protected final static char[] BRACKETS = {'{', '}', '(', ')', '[', ']', '<', '>'};

    static {
        // SQL editor preferences. Do this here because it initializes display
        // (that's why we can't run it in prefs initializer classes which run before workbench creation)
        {
            IPreferenceStore editorStore = EditorsUI.getPreferenceStore();
            editorStore.setDefault(SQLPreferenceConstants.MATCHING_BRACKETS, true);
            //editorStore.setDefault(SQLPreferenceConstants.MATCHING_BRACKETS_COLOR, "128,128,128"); //$NON-NLS-1$
        }
    }

    private final Object LOCK_OBJECT = new Object();

    @NotNull
    private final SQLSyntaxManager syntaxManager;
    @NotNull
    private final SQLRuleManager ruleManager;
    private ProjectionSupport projectionSupport;

    private ProjectionAnnotationModel annotationModel;
    //private Map<Annotation, Position> curAnnotations;

    //private IAnnotationAccess annotationAccess;
    private boolean hasVerticalRuler = true;
    private SQLTemplatesPage templatesPage;
    private IPropertyChangeListener themeListener;
    private SQLEditorControl editorControl;

    //private ActivationListener activationListener = new ActivationListener();
    private EditorSelectionChangedListener selectionChangedListener = new EditorSelectionChangedListener();
    private Annotation[] occurrenceAnnotations = null;
    private boolean markOccurrencesUnderCursor;
    private boolean markOccurrencesForSelection;
    private OccurrencesFinderJob occurrencesFinderJob;
    private OccurrencesFinderJobCanceler occurrencesFinderJobCanceler;
    private ICharacterPairMatcher characterPairMatcher;
    private SQLEditorCompletionContext completionContext;

    public SQLEditorBase() {
        super();
        syntaxManager = new SQLSyntaxManager();
        ruleManager = new SQLRuleManager(syntaxManager);
        themeListener = new IPropertyChangeListener() {
            long lastUpdateTime = 0;

            @Override
            public void propertyChange(PropertyChangeEvent event) {
                if (event.getProperty().equals(IThemeManager.CHANGE_CURRENT_THEME) ||
                    event.getProperty().startsWith("org.jkiss.dbeaver.sql.editor")) {
                    if (lastUpdateTime > 0 && System.currentTimeMillis() - lastUpdateTime < 500) {
                        // Do not update too often (theme change may trigger this hundreds of times)
                        return;
                    }
                    lastUpdateTime = System.currentTimeMillis();
                    UIUtils.asyncExec(() -> {
                        ISourceViewer sourceViewer = getSourceViewer();
                        if (sourceViewer != null) {
                            reloadSyntaxRules();
                            // Reconfigure to let comments/strings colors to take effect
                            sourceViewer.configure(getSourceViewerConfiguration());
                        }
                    });
                }
            }
        };
        PlatformUI.getWorkbench().getThemeManager().addPropertyChangeListener(themeListener);

        //setDocumentProvider(new SQLDocumentProvider());
        setSourceViewerConfiguration(new SQLEditorSourceViewerConfiguration(this, getPreferenceStore()));
        setKeyBindingScopes(getKeyBindingContexts());  //$NON-NLS-1$

        completionContext = new SQLEditorCompletionContext(this);
    }

    @Override
    protected boolean isReadOnly() {
        IDocumentProvider provider = getDocumentProvider();
        return provider instanceof IDocumentProviderExtension &&
            ((IDocumentProviderExtension) provider).isReadOnly(getEditorInput());
    }

    public static boolean isBigScript(@Nullable IEditorInput editorInput) {
        if (editorInput != null) {
            File file = EditorUtils.getLocalFileFromInput(editorInput);
            if (file != null && file.length() > MAX_FILE_LENGTH_FOR_RULES) {
                return true;
            }
        }
        return false;
    }

    static boolean isReadEmbeddedBinding() {
        return DBWorkbench.getPlatform().getPreferenceStore().getBoolean(SQLPreferenceConstants.SCRIPT_BIND_EMBEDDED_READ);
    }

    static boolean isWriteEmbeddedBinding() {
        return DBWorkbench.getPlatform().getPreferenceStore().getBoolean(SQLPreferenceConstants.SCRIPT_BIND_EMBEDDED_WRITE);
    }

    protected void handleInputChange(IEditorInput input) {
        if (isBigScript(input)) {
            uninstallOccurrencesFinder();
        } else {
            setMarkingOccurrences(
                DBWorkbench.getPlatform().getPreferenceStore().getBoolean(SQLPreferenceConstants.MARK_OCCURRENCES_UNDER_CURSOR),
                DBWorkbench.getPlatform().getPreferenceStore().getBoolean(SQLPreferenceConstants.MARK_OCCURRENCES_FOR_SELECTION));
        }
    }

    @Override
    protected void updateSelectionDependentActions() {
        super.updateSelectionDependentActions();
        updateStatusField(STATS_CATEGORY_SELECTION_STATE);
    }

    protected String[] getKeyBindingContexts() {
        return new String[]{
            TEXT_EDITOR_CONTEXT,
            SQLEditorContributions.SQL_EDITOR_CONTEXT,
            SQLEditorContributions.SQL_EDITOR_SCRIPT_CONTEXT,
            SQLEditorContributions.SQL_EDITOR_CONTROL_CONTEXT};
    }

    @Override
    protected void initializeEditor() {
        super.initializeEditor();
        this.markOccurrencesUnderCursor = DBWorkbench.getPlatform().getPreferenceStore().getBoolean(SQLPreferenceConstants.MARK_OCCURRENCES_UNDER_CURSOR);
        this.markOccurrencesForSelection = DBWorkbench.getPlatform().getPreferenceStore().getBoolean(SQLPreferenceConstants.MARK_OCCURRENCES_FOR_SELECTION);
        setEditorContextMenuId(SQLEditorContributions.SQL_EDITOR_CONTEXT_MENU_ID);
        setRulerContextMenuId(SQLEditorContributions.SQL_RULER_CONTEXT_MENU_ID);
    }

    public DBPDataSource getDataSource() {
        DBCExecutionContext context = getExecutionContext();
        return context == null ? null : context.getDataSource();
    }

    public DBPPreferenceStore getActivePreferenceStore() {
        if (this instanceof IDataSourceContainerProvider) {
            DBPDataSourceContainer container = ((IDataSourceContainerProvider) this).getDataSourceContainer();
            if (container != null) {
                return container.getPreferenceStore();
            }
        }
        DBPDataSource dataSource = getDataSource();
        return dataSource == null ? DBWorkbench.getPlatform().getPreferenceStore() : dataSource.getContainer().getPreferenceStore();
    }

    @Override
    protected void handlePreferenceStoreChanged(PropertyChangeEvent event) {
        String property = event.getProperty();
        if (SQLPreferenceConstants.MARK_OCCURRENCES_UNDER_CURSOR.equals(property) || SQLPreferenceConstants.MARK_OCCURRENCES_FOR_SELECTION.equals(property)) {
            setMarkingOccurrences(
                DBWorkbench.getPlatform().getPreferenceStore().getBoolean(SQLPreferenceConstants.MARK_OCCURRENCES_UNDER_CURSOR),
                DBWorkbench.getPlatform().getPreferenceStore().getBoolean(SQLPreferenceConstants.MARK_OCCURRENCES_FOR_SELECTION));
        } else {
            super.handlePreferenceStoreChanged(event);
        }
    }

    @NotNull
    public SQLDialect getSQLDialect() {
        DBPDataSource dataSource = getDataSource();
        // Refresh syntax
        if (dataSource instanceof SQLDataSource) {
            return ((SQLDataSource) dataSource).getSQLDialect();
        }
        return BasicSQLDialect.INSTANCE;
    }

    @NotNull
    public SQLSyntaxManager getSyntaxManager() {
        return syntaxManager;
    }

    @NotNull
    public SQLRuleManager getRuleManager() {
        return ruleManager;
    }

    public ProjectionAnnotationModel getAnnotationModel() {
        return annotationModel;
    }

    public SQLEditorSourceViewerConfiguration getViewerConfiguration() {
        return (SQLEditorSourceViewerConfiguration) super.getSourceViewerConfiguration();
    }

    @Override
    public void createPartControl(Composite parent) {
        setRangeIndicator(new DefaultRangeIndicator());

        editorControl = new SQLEditorControl(parent, this);
        super.createPartControl(editorControl);

        //this.getEditorSite().getShell().addShellListener(this.activationListener);
        this.selectionChangedListener = new EditorSelectionChangedListener();
        this.selectionChangedListener.install(this.getSelectionProvider());

        if (this.markOccurrencesUnderCursor || this.markOccurrencesForSelection) {
            this.installOccurrencesFinder();
        }

        ProjectionViewer viewer = (ProjectionViewer) getSourceViewer();
        projectionSupport = new ProjectionSupport(
            viewer,
            getAnnotationAccess(),
            getSharedColors());
        projectionSupport.addSummarizableAnnotationType("org.eclipse.ui.workbench.texteditor.error"); //$NON-NLS-1$
        projectionSupport.addSummarizableAnnotationType("org.eclipse.ui.workbench.texteditor.warning"); //$NON-NLS-1$
        projectionSupport.install();

        viewer.doOperation(ProjectionViewer.TOGGLE);

        annotationModel = viewer.getProjectionAnnotationModel();

        // Symbol inserter
        {
            SQLSymbolInserter symbolInserter = new SQLSymbolInserter(this);

            DBPPreferenceStore preferenceStore = getActivePreferenceStore();
            boolean closeSingleQuotes = preferenceStore.getBoolean(SQLPreferenceConstants.SQLEDITOR_CLOSE_SINGLE_QUOTES);
            boolean closeDoubleQuotes = preferenceStore.getBoolean(SQLPreferenceConstants.SQLEDITOR_CLOSE_DOUBLE_QUOTES);
            boolean closeBrackets = preferenceStore.getBoolean(SQLPreferenceConstants.SQLEDITOR_CLOSE_BRACKETS);

            symbolInserter.setCloseSingleQuotesEnabled(closeSingleQuotes);
            symbolInserter.setCloseDoubleQuotesEnabled(closeDoubleQuotes);
            symbolInserter.setCloseBracketsEnabled(closeBrackets);

            ISourceViewer sourceViewer = getSourceViewer();
            if (sourceViewer instanceof ITextViewerExtension) {
                ((ITextViewerExtension) sourceViewer).prependVerifyKeyListener(symbolInserter);
            }
        }

        {
            // Context listener
            EditorUtils.trackControlContext(getSite(), getViewer().getTextWidget(), SQLEditorContributions.SQL_EDITOR_CONTROL_CONTEXT);
        }
    }

    public SQLEditorControl getEditorControlWrapper() {
        return editorControl;
    }

    @Override
    public void updatePartControl(IEditorInput input) {
        super.updatePartControl(input);
    }

    protected IOverviewRuler createOverviewRuler(ISharedTextColors sharedColors) {
        if (isOverviewRulerVisible()) {
            return super.createOverviewRuler(sharedColors);
        } else {
            return new OverviewRuler(getAnnotationAccess(), 0, sharedColors);
        }
    }

    @Override
    protected boolean isOverviewRulerVisible() {
        return false;
    }

    // Most left ruler
    @Override
    protected IVerticalRulerColumn createAnnotationRulerColumn(CompositeRuler ruler) {
        if (isAnnotationRulerVisible()) {
            return super.createAnnotationRulerColumn(ruler);
        } else {
            return new AnnotationRulerColumn(0, getAnnotationAccess());
        }
    }

    protected boolean isAnnotationRulerVisible() {
        return false;
    }

    @Override
    protected IVerticalRuler createVerticalRuler() {
        return hasVerticalRuler ? super.createVerticalRuler() : new VerticalRuler(0);
    }

    public void setHasVerticalRuler(boolean hasVerticalRuler) {
        this.hasVerticalRuler = hasVerticalRuler;
    }

    protected ISharedTextColors getSharedColors() {
        return UIUtils.getSharedTextColors();
    }

    @Override
    protected void doSetInput(IEditorInput input) throws CoreException {
        handleInputChange(input);
        super.doSetInput(input);
    }

    @Override
    public void doSave(IProgressMonitor progressMonitor) {
        super.doSave(progressMonitor);

        handleInputChange(getEditorInput());
    }

    @Override
    protected ISourceViewer createSourceViewer(Composite parent, IVerticalRuler ruler, int styles) {
        fAnnotationAccess = getAnnotationAccess();
        fOverviewRuler = createOverviewRuler(getSharedColors());

        SQLEditorSourceViewer sourceViewer = createSourceViewer(parent, ruler, styles, fOverviewRuler);

        getSourceViewerDecorationSupport(sourceViewer);

        return sourceViewer;
    }

    protected void configureSourceViewerDecorationSupport(SourceViewerDecorationSupport support) {
        char[] matchChars = BRACKETS; //which brackets to match
        try {
            characterPairMatcher = new SQLCharacterPairMatcher(this, matchChars,
                SQLParserPartitions.SQL_PARTITIONING,
                true);
        } catch (Throwable e) {
            // If we below Eclipse 4.2.1
            characterPairMatcher = new SQLCharacterPairMatcher(this, matchChars, SQLParserPartitions.SQL_PARTITIONING);
        }
        support.setCharacterPairMatcher(characterPairMatcher);
        support.setMatchingCharacterPainterPreferenceKeys(SQLPreferenceConstants.MATCHING_BRACKETS, SQLPreferenceConstants.MATCHING_BRACKETS_COLOR);
        super.configureSourceViewerDecorationSupport(support);
    }

    @NotNull
    protected SQLEditorSourceViewer createSourceViewer(Composite parent, IVerticalRuler ruler, int styles, IOverviewRuler overviewRuler) {
        return new SQLEditorSourceViewer(
            parent,
            ruler,
            overviewRuler,
            true,
            styles);
    }

    @Override
    protected IAnnotationAccess createAnnotationAccess() {
        return new SQLMarkerAnnotationAccess();
    }
/*
    protected void adjustHighlightRange(int offset, int length)
    {
        ISourceViewer viewer = getSourceViewer();
        if (viewer instanceof ITextViewerExtension5) {
            ITextViewerExtension5 extension = (ITextViewerExtension5) viewer;
            extension.exposeModelRange(new Region(offset, length));
        }
    }
*/

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getAdapter(Class<T> required) {
        if (projectionSupport != null) {
            Object adapter = projectionSupport.getAdapter(
                getSourceViewer(), required);
            if (adapter != null)
                return (T) adapter;
        }
        if (ITemplatesPage.class.equals(required)) {
            return (T) getTemplatesPage();
        }

        return super.getAdapter(required);
    }

    public SQLTemplatesPage getTemplatesPage() {
        if (templatesPage == null)
            templatesPage = new SQLTemplatesPage(this);
        return templatesPage;
    }

    @Override
    public void dispose() {
        if (this.selectionChangedListener != null) {
            this.selectionChangedListener.uninstall(this.getSelectionProvider());
            this.selectionChangedListener = null;
        }
/*
        if (this.activationListener != null) {
            Shell shell = this.getEditorSite().getShell();
            if (shell != null && !shell.isDisposed()) {
                shell.removeShellListener(this.activationListener);
            }
            this.activationListener = null;
        }
*/

        if (themeListener != null) {
            PlatformUI.getWorkbench().getThemeManager().removePropertyChangeListener(themeListener);
            themeListener = null;
        }

        super.dispose();
    }

    @Override
    protected void createActions() {
        super.createActions();

        ResourceBundle bundle = ResourceBundle.getBundle(SQLEditorMessages.BUNDLE_NAME);

        IAction a = new TextOperationAction(
            bundle,
            SQLEditorContributor.getActionResourcePrefix(SQLEditorContributor.ACTION_CONTENT_ASSIST_PROPOSAL),
            this,
            ISourceViewer.CONTENTASSIST_PROPOSALS);
        a.setActionDefinitionId(ITextEditorActionDefinitionIds.CONTENT_ASSIST_PROPOSALS);
        setAction(SQLEditorContributor.ACTION_CONTENT_ASSIST_PROPOSAL, a);

        a = new TextOperationAction(
            bundle,
            SQLEditorContributor.getActionResourcePrefix(SQLEditorContributor.ACTION_CONTENT_ASSIST_TIP),
            this,
            ISourceViewer.CONTENTASSIST_CONTEXT_INFORMATION);
        a.setActionDefinitionId(ITextEditorActionDefinitionIds.CONTENT_ASSIST_CONTEXT_INFORMATION);
        setAction(SQLEditorContributor.ACTION_CONTENT_ASSIST_TIP, a);

        a = new TextOperationAction(
            bundle,
            SQLEditorContributor.getActionResourcePrefix(SQLEditorContributor.ACTION_CONTENT_ASSIST_INFORMATION),
            this,
            ISourceViewer.INFORMATION);
        a.setActionDefinitionId(ITextEditorActionDefinitionIds.SHOW_INFORMATION);
        setAction(SQLEditorContributor.ACTION_CONTENT_ASSIST_INFORMATION, a);

        a = new TextOperationAction(
            bundle,
            SQLEditorContributor.getActionResourcePrefix(SQLEditorContributor.ACTION_CONTENT_FORMAT_PROPOSAL),
            this,
            ISourceViewer.FORMAT);
        a.setActionDefinitionId(BaseTextEditorCommands.CMD_CONTENT_FORMAT);
        setAction(SQLEditorContributor.ACTION_CONTENT_FORMAT_PROPOSAL, a);

        setAction(ITextEditorActionConstants.CONTEXT_PREFERENCES, new ShowPreferencesAction());
/*
        // Add the task action to the Edit pulldown menu (bookmark action is  'free')
        ResourceAction ra = new AddTaskAction(bundle, "AddTask.", this);
        ra.setHelpContextId(ITextEditorHelpContextIds.ADD_TASK_ACTION);
        ra.setActionDefinitionId(ITextEditorActionDefinitionIds.ADD_TASK);
        setAction(IDEActionFactory.ADD_TASK.getId(), ra);
*/
    }

    // Exclude input additions. Get rid of tons of crap from debug/team extensions
    @Override
    protected boolean isEditorInputIncludedInContextMenu() {
        return false;
    }

    @Override
    public void editorContextMenuAboutToShow(IMenuManager menu) {
        menu.add(new GroupMarker(GROUP_SQL_ADDITIONS));

        super.editorContextMenuAboutToShow(menu);

        //menu.add(new Separator("content"));//$NON-NLS-1$
        addAction(menu, GROUP_SQL_EXTRAS, SQLEditorContributor.ACTION_CONTENT_ASSIST_PROPOSAL);
        addAction(menu, GROUP_SQL_EXTRAS, SQLEditorContributor.ACTION_CONTENT_ASSIST_TIP);
        addAction(menu, GROUP_SQL_EXTRAS, SQLEditorContributor.ACTION_CONTENT_ASSIST_INFORMATION);
        menu.insertBefore(ITextEditorActionConstants.GROUP_COPY, ActionUtils.makeCommandContribution(getSite(), SQLEditorCommands.CMD_NAVIGATE_OBJECT));

        if (!isReadOnly() && getTextViewer().isEditable()) {
            MenuManager formatMenu = new MenuManager(SQLEditorMessages.sql_editor_menu_format, "format");
            IAction formatAction = getAction(SQLEditorContributor.ACTION_CONTENT_FORMAT_PROPOSAL);
            if (formatAction != null) {
                formatMenu.add(formatAction);
            }
            formatMenu.add(ActionUtils.makeCommandContribution(getSite(), "org.jkiss.dbeaver.ui.editors.sql.morph.delimited.list"));
            formatMenu.add(getAction(ITextEditorActionConstants.UPPER_CASE));
            formatMenu.add(getAction(ITextEditorActionConstants.LOWER_CASE));
            formatMenu.add(new Separator());
            formatMenu.add(ActionUtils.makeCommandContribution(getSite(), "org.jkiss.dbeaver.ui.editors.sql.word.wrap"));
            formatMenu.add(ActionUtils.makeCommandContribution(getSite(), "org.jkiss.dbeaver.ui.editors.sql.comment.single"));
            formatMenu.add(ActionUtils.makeCommandContribution(getSite(), "org.jkiss.dbeaver.ui.editors.sql.comment.multi"));
            menu.insertAfter(GROUP_SQL_ADDITIONS, formatMenu);
        }

        //menu.remove(IWorkbenchActionConstants.MB_ADDITIONS);
    }

    public void reloadSyntaxRules() {
        // Refresh syntax
        SQLDialect dialect = getSQLDialect();
        syntaxManager.init(dialect, getActivePreferenceStore());
        ruleManager.refreshRules(getDataSource(), getEditorInput());

        IDocument document = getDocument();
        if (document instanceof IDocumentExtension3) {
            IDocumentPartitioner partitioner = new FastPartitioner(
                new SQLPartitionScanner(dialect),
                SQLParserPartitions.SQL_CONTENT_TYPES);
            partitioner.connect(document);
            try {
                ((IDocumentExtension3)document).setDocumentPartitioner(SQLParserPartitions.SQL_PARTITIONING, partitioner);
            } catch (Throwable e) {
                log.warn("Error setting SQL partitioner", e); //$NON-NLS-1$
            }

            ProjectionViewer projectionViewer = (ProjectionViewer) getSourceViewer();
            if (projectionViewer != null && projectionViewer.getAnnotationModel() != null && document.getLength() > 0) {
                // Refresh viewer
                //projectionViewer.getTextWidget().redraw();
                try {
                    projectionViewer.reinitializeProjection();
                } catch (Throwable ex) {
                    // We can catch OutOfMemory here for too big/complex documents
                    log.warn("Can't initialize SQL syntax projection", ex); //$NON-NLS-1$
                }
            }
        }

        final IVerticalRuler verticalRuler = getVerticalRuler();

        /*if (isReadOnly()) {
            //Color fgColor = ruleManager.getColor(SQLConstants.CONFIG_COLOR_TEXT);
            Color bgColor = ruleManager.getColor(SQLConstants.CONFIG_COLOR_DISABLED);
            TextViewer textViewer = getTextViewer();
            if (textViewer != null) {
                final StyledText textWidget = textViewer.getTextWidget();
                textWidget.setBackground(bgColor);
                if (verticalRuler != null && verticalRuler.getControl() != null) {
                    verticalRuler.getControl().setBackground(bgColor);
                }
            }
        }*/

        // Update configuration
        if (getSourceViewerConfiguration() instanceof SQLEditorSourceViewerConfiguration) {
            ((SQLEditorSourceViewerConfiguration) getSourceViewerConfiguration()).onDataSourceChange();
        }
        if (verticalRuler != null) {
            verticalRuler.update();
        }
    }

    public boolean hasActiveQuery() {
        IDocument document = getDocument();
        if (document == null) {
            return false;
        }
        ISelectionProvider selectionProvider = getSelectionProvider();
        if (selectionProvider == null) {
            return false;
        }
        ITextSelection selection = (ITextSelection) selectionProvider.getSelection();
        String selText = selection.getText();
        if (CommonUtils.isEmpty(selText) && selection.getOffset() >= 0 && selection.getOffset() < document.getLength()) {
            try {
                IRegion lineRegion = document.getLineInformationOfOffset(selection.getOffset());
                selText = document.get(lineRegion.getOffset(), lineRegion.getLength());
            } catch (BadLocationException e) {
                log.warn(e);
                return false;
            }
        }

        return !CommonUtils.isEmptyTrimmed(selText);
    }

    @Nullable
    public SQLScriptElement extractActiveQuery() {
        SQLScriptElement element;
        ITextSelection selection = (ITextSelection) getSelectionProvider().getSelection();
        String selText = selection.getText();

        if (getActivePreferenceStore().getBoolean(ModelPreferences.QUERY_REMOVE_TRAILING_DELIMITER)) {
            selText = SQLUtils.trimQueryStatement(getSyntaxManager(), selText, !syntaxManager.getDialect().isDelimiterAfterQuery());
        }
        if (!CommonUtils.isEmpty(selText)) {
            SQLScriptElement parsedElement = parseQuery(getDocument(), selection.getOffset(), selection.getOffset() + selection.getLength(), selection.getOffset(), false, false);
            if (parsedElement instanceof SQLControlCommand) {
                // This is a command
                element = parsedElement;
            } else {
                // Use selected query as is
                selText = SQLUtils.fixLineFeeds(selText);
                element = new SQLQuery(getDataSource(), selText, selection.getOffset(), selection.getLength());
            }
        } else if (selection.getOffset() >= 0) {
            element = extractQueryAtPos(selection.getOffset());
        } else {
            element = null;
        }
        // Check query do not ends with delimiter
        // (this may occur if user selected statement including delimiter)
        if (element == null || CommonUtils.isEmpty(element.getText())) {
            return null;
        }
        if (element instanceof SQLQuery && getActivePreferenceStore().getBoolean(ModelPreferences.SQL_PARAMETERS_ENABLED)) {
            ((SQLQuery) element).setParameters(parseParameters(getDocument(), (SQLQuery) element));
        }
        return element;
    }

    public SQLScriptElement extractQueryAtPos(int currentPos) {
        IDocument document = getDocument();
        if (document == null || document.getLength() == 0) {
            return null;
        }
        final int docLength = document.getLength();
        IDocumentPartitioner partitioner = document instanceof IDocumentExtension3 ? ((IDocumentExtension3)document).getDocumentPartitioner(SQLParserPartitions.SQL_PARTITIONING) : null;
        if (partitioner != null) {
            // Move to default partition. We don't want to be in the middle of multi-line comment or string
            while (currentPos < docLength && isMultiCommentPartition(partitioner, currentPos)) {
                currentPos++;
            }
        }
        // Extract part of document between empty lines
        int startPos = 0;
        boolean useBlankLines = syntaxManager.isBlankLineDelimiter();
        final String[] statementDelimiters = syntaxManager.getStatementDelimiters();
        int lastPos = currentPos >= docLength ? docLength - 1 : currentPos;

        try {
            int currentLine = document.getLineOfOffset(currentPos);
            if (useBlankLines) {
                if (TextUtils.isEmptyLine(document, currentLine)) {
                    if (currentLine == 0) {
                        return null;
                    }
                    currentLine--;
                    if (TextUtils.isEmptyLine(document, currentLine)) {
                        // Prev line empty too. No chance.
                        return null;
                    }
                }
            }

            int lineOffset = document.getLineOffset(currentLine);
            int firstLine = currentLine;
            while (firstLine > 0) {
                if (useBlankLines) {
                    if (TextUtils.isEmptyLine(document, firstLine) &&
                        isDefaultPartition(partitioner, document.getLineOffset(firstLine))) {
                        break;
                    }
                }
                if (currentLine == firstLine) {
                    for (String delim : statementDelimiters) {
                        if (Character.isLetterOrDigit(delim.charAt(0))) {
                            // Skip literal delimiters
                            continue;
                        }
                        final int offset = TextUtils.getOffsetOf(document, firstLine, delim);
                        if (offset >= 0 ) {
                            int delimOffset = document.getLineOffset(firstLine) + offset + delim.length();
                            if (isDefaultPartition(partitioner, delimOffset)) {
                                if (currentPos > startPos) {
                                    if (docLength > delimOffset) {
                                        boolean hasValuableChars = false;
                                        for (int i = delimOffset; i <= lastPos; i++) {
                                            if (!Character.isWhitespace(document.getChar(i))) {
                                                hasValuableChars = true;
                                                break;
                                            }
                                        }
                                        if (hasValuableChars) {
                                            startPos = delimOffset;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                firstLine--;
            }
            if (startPos == 0) {
                startPos = document.getLineOffset(firstLine);
            }

            // Move currentPos at line begin
            currentPos = lineOffset;
        } catch (BadLocationException e) {
            log.warn(e);
        }
        return parseQuery(document, startPos, document.getLength(), currentPos, false, false);
    }

    public SQLScriptElement extractNextQuery(boolean next) {
        ITextSelection selection = (ITextSelection) getSelectionProvider().getSelection();
        int offset = selection.getOffset();
        SQLScriptElement curElement = extractQueryAtPos(offset);
        if (curElement == null) {
            return null;
        }

        IDocument document = getDocument();
        if (document == null) {
            return null;
        }
        try {
            int docLength = document.getLength();
            int curPos;
            if (next) {
                final String[] statementDelimiters = syntaxManager.getStatementDelimiters();
                curPos = curElement.getOffset() + curElement.getLength();
                while (curPos < docLength) {
                    char c = document.getChar(curPos);
                    if (!Character.isWhitespace(c)) {
                        boolean isDelimiter = false;
                        for (String delim : statementDelimiters) {
                            if (delim.indexOf(c) != -1) {
                                isDelimiter = true;
                            }
                        }
                        if (!isDelimiter) {
                            break;
                        }
                    }
                    curPos++;
                }
            } else {
                curPos = curElement.getOffset() - 1;
                while (curPos >= 0) {
                    char c = document.getChar(curPos);
                    if (!Character.isWhitespace(c)) {
                        break;
                    }
                    curPos--;
                }
            }
            if (curPos <= 0 || curPos >= docLength) {
                return null;
            }
            return extractQueryAtPos(curPos);
        } catch (BadLocationException e) {
            log.warn(e);
            return null;
        }
    }

    private static boolean isDefaultPartition(IDocumentPartitioner partitioner, int currentPos) {
        return partitioner == null || IDocument.DEFAULT_CONTENT_TYPE.equals(partitioner.getContentType(currentPos));
    }

    private static boolean isMultiCommentPartition(IDocumentPartitioner partitioner, int currentPos) {
        return partitioner != null && SQLParserPartitions.CONTENT_TYPE_SQL_MULTILINE_COMMENT.equals(partitioner.getContentType(currentPos));
    }

    private void startScriptEvaluation() {
        ruleManager.startEval();
    }

    private void endScriptEvaluation() {
        ruleManager.endEval();
    }

    public List<SQLScriptElement> extractScriptQueries(int startOffset, int length, boolean scriptMode, boolean keepDelimiters, boolean parseParameters) {
        List<SQLScriptElement> queryList = new ArrayList<>();

        IDocument document = getDocument();
        if (document == null) {
            return queryList;
        }

        this.startScriptEvaluation();
        try {
            for (int queryOffset = startOffset; ; ) {
                SQLScriptElement query = parseQuery(document, queryOffset, startOffset + length, queryOffset, scriptMode, keepDelimiters);
                if (query == null) {
                    break;
                }
                queryList.add(query);
                queryOffset = query.getOffset() + query.getLength();
            }
        } finally {
            this.endScriptEvaluation();
        }

        if (parseParameters && getActivePreferenceStore().getBoolean(ModelPreferences.SQL_PARAMETERS_ENABLED)) {
            // Parse parameters
            for (SQLScriptElement query : queryList) {
                if (query instanceof SQLQuery) {
                    ((SQLQuery) query).setParameters(parseParameters(getDocument(), (SQLQuery) query));
                }
            }
        }
        return queryList;
    }

    public SQLCompletionContext getCompletionContext() {
        return completionContext;
    }

    private static class ScriptBlockInfo {
        final ScriptBlockInfo parent;
        boolean isHeader; // block started by DECLARE, FUNCTION, etc

        public ScriptBlockInfo(ScriptBlockInfo parent, boolean isHeader) {
            this.parent = parent;
            this.isHeader = isHeader;
        }
    }

    protected SQLScriptElement parseQuery(final IDocument document, final int startPos, final int endPos, final int currentPos, final boolean scriptMode, final boolean keepDelimiters) {
        int length = endPos - startPos;
        if (length <= 0 || length > document.getLength()) {
            return null;
        }
        SQLDialect dialect = getSQLDialect();

        // Parse range
        boolean useBlankLines = !scriptMode && syntaxManager.isBlankLineDelimiter();
        ruleManager.setRange(document, startPos, endPos - startPos);
        int statementStart = startPos;
        boolean hasValuableTokens = false;
        ScriptBlockInfo curBlock = null;
        boolean hasBlocks = false;
        String blockTogglePattern = null;
        int lastTokenLineFeeds = 0;
        int prevNotEmptyTokenType = SQLToken.T_UNKNOWN;
        String lastKeyword = null;
        for (; ; ) {
            IToken token = ruleManager.nextToken();
            int tokenOffset = ruleManager.getTokenOffset();
            int tokenLength = ruleManager.getTokenLength();
            int tokenType = token instanceof SQLToken ? ((SQLToken) token).getType() : SQLToken.T_UNKNOWN;
            if (tokenOffset < startPos) {
                // This may happen with EOF tokens (bug in jface?)
                return null;
            }

            boolean isDelimiter = tokenType == SQLToken.T_DELIMITER;
            boolean isControl = false;
            String delimiterText = null;
            try {
                if (isDelimiter) {
                    // Save delimiter text
                    try {
                        delimiterText = document.get(tokenOffset, tokenLength);
                    } catch (BadLocationException e) {
                        log.debug(e);
                    }
                } else if (useBlankLines && token.isWhitespace() && tokenLength >= 1) {
                    // Check for blank line delimiter
                    if (lastTokenLineFeeds + countLineFeeds(document, tokenOffset, tokenLength) >= 2) {
                        isDelimiter = true;
                    }
                }
                lastTokenLineFeeds = 0;
                if (tokenLength == 1) {
                    // Check for bracket block begin/end
                    try {
                        char aChar = document.getChar(tokenOffset);
                        if (aChar == '(' || aChar == '{' || aChar == '[') {
                            curBlock = new ScriptBlockInfo(curBlock, false);
                        } else if (aChar == ')' || aChar == '}' || aChar == ']') {
                            if (curBlock != null) {
                                curBlock = curBlock.parent;
                            }
                        }
                    } catch (BadLocationException e) {
                        log.warn(e);
                    }
                }
                if (tokenType == SQLToken.T_BLOCK_BEGIN && prevNotEmptyTokenType == SQLToken.T_BLOCK_END) {
                    // This is a tricky thing.
                    // In some dialects block end looks like END CASE, END LOOP. It is parsed as
                    // Block end followed by block begin (as CASE and LOOP are block begin tokens)
                    // So let's ignore block begin if previos token was block end and there were no delimtiers.
                    tokenType = SQLToken.T_UNKNOWN;
                }

                if (tokenType == SQLToken.T_BLOCK_HEADER) {
                    curBlock = new ScriptBlockInfo(curBlock, true);
                    hasBlocks = true;
                } else if (tokenType == SQLToken.T_BLOCK_TOGGLE) {
                    String togglePattern;
                    try {
                        togglePattern = document.get(tokenOffset, tokenLength);
                    } catch (BadLocationException e) {
                        log.warn(e);
                        togglePattern = "";
                    }
                    // Second toggle pattern must be the same as first one.
                    // Toggles can be nested (PostgreSQL) and we need to count only outer
                    if (curBlock != null && curBlock.parent == null && togglePattern.equals(blockTogglePattern)) {
                        curBlock = curBlock.parent;
                        blockTogglePattern = null;
                    } else if (curBlock == null && blockTogglePattern == null) {
                        curBlock = new ScriptBlockInfo(curBlock, false);
                        blockTogglePattern = togglePattern;
                    } else {
                        log.debug("Block toggle token inside another block. Can't process it");
                    }
                    hasBlocks = true;
                } else if (tokenType == SQLToken.T_BLOCK_BEGIN) {
                    if (curBlock == null || !curBlock.isHeader) {
                        curBlock = new ScriptBlockInfo(curBlock, false);
                    } else if (curBlock != null) {
                        curBlock.isHeader = false;
                    }
                    hasBlocks = true;
                } else if (curBlock != null && tokenType == SQLToken.T_BLOCK_END) {
                    // Sometimes query contains END clause without BEGIN. E.g. CASE, IF, etc.
                    // This END doesn't mean block
                    if (curBlock != null) {
                        curBlock = curBlock.parent;
                    }
                } else if (isDelimiter && curBlock != null) {
                    // Delimiter in some brackets - ignore it
                    continue;
                } else if (tokenType == SQLToken.T_SET_DELIMITER || tokenType == SQLToken.T_CONTROL) {
                    isDelimiter = true;
                    isControl = true;
                } else if (tokenType == SQLToken.T_COMMENT) {
                    lastTokenLineFeeds = tokenLength < 2 ? 0 : countLineFeeds(document, tokenOffset + tokenLength - 2, 2);
                }

                if (tokenLength > 0 && !token.isWhitespace()) {
                    switch (tokenType) {
                        case SQLToken.T_BLOCK_BEGIN:
                        case SQLToken.T_BLOCK_END:
                        case SQLToken.T_BLOCK_TOGGLE:
                        case SQLToken.T_BLOCK_HEADER:
                        case SQLToken.T_UNKNOWN:
                            try {
                                lastKeyword = document.get(tokenOffset, tokenLength);
                            } catch (BadLocationException e) {
                                log.error("Error getting first keyword", e);
                            }
                            break;
                    }
                }

                boolean cursorInsideToken = currentPos >= tokenOffset && currentPos < tokenOffset + tokenLength;
                if (isControl && (scriptMode || cursorInsideToken) && !hasValuableTokens) {
                    // Control query
                    try {
                        String controlText = document.get(tokenOffset, tokenLength);
                        String commandId = null;
                        if (token instanceof SQLControlToken) {
                            commandId = ((SQLControlToken) token).getCommandId();
                        }
                        SQLControlCommand command = new SQLControlCommand(
                            getDataSource(),
                            syntaxManager,
                            controlText.trim(),
                            commandId,
                            tokenOffset,
                            tokenLength,
                            tokenType == SQLToken.T_SET_DELIMITER);
                        if (command.isEmptyCommand() ||
                            (command.getCommandId() != null &&
                                SQLCommandsRegistry.getInstance().getCommandHandler(command.getCommandId()) != null)) {
                            return command;
                        }
                        // This is not a valid command
                        isControl = false;
                    } catch (BadLocationException e) {
                        log.warn("Can't extract control statement", e); //$NON-NLS-1$
                        return null;
                    }
                }
                if (hasValuableTokens && (token.isEOF() || (isDelimiter && tokenOffset >= currentPos) || tokenOffset > endPos)) {
                    if (tokenOffset > endPos) {
                        tokenOffset = endPos;
                    }
                    if (tokenOffset >= document.getLength()) {
                        // Sometimes (e.g. when comment finishing script text)
                        // last token offset is beyond document range
                        tokenOffset = document.getLength();
                    }
                    assert (tokenOffset >= currentPos);
                    try {

                        // remove leading spaces
                        while (statementStart < tokenOffset && Character.isWhitespace(document.getChar(statementStart))) {
                            statementStart++;
                        }
                        // remove trailing spaces
/*
                        while (statementStart < tokenOffset && Character.isWhitespace(document.getChar(tokenOffset - 1))) {
                            tokenOffset--;
                            tokenLength++;
                        }
*/
                        if (tokenOffset == statementStart) {
                            // Empty statement
                            if (token.isEOF()) {
                                return null;
                            }
                            statementStart = tokenOffset + tokenLength;
                            continue;
                        }
                        String queryText = document.get(statementStart, tokenOffset - statementStart);
                        queryText = SQLUtils.fixLineFeeds(queryText);

                        if (isDelimiter && (keepDelimiters ||
                            (hasBlocks && dialect.isDelimiterAfterQuery()) ||
                            (dialect.isDelimiterAfterBlock() && SQLConstants.BLOCK_END.equals(lastKeyword))))
                        {
                            if (delimiterText != null && delimiterText.equals(SQLConstants.DEFAULT_STATEMENT_DELIMITER)) {
                                // Add delimiter in the end of query. Do this only for semicolon delimiters.
                                // For SQL server add it in the end of query. For Oracle only after END clause
                                // Quite dirty workaround needed for Oracle and SQL Server.
                                // TODO: move this transformation into SQLDialect
                                queryText += delimiterText;
                            }
                        }
                        int queryEndPos = tokenOffset;
                        if (tokenType == SQLToken.T_DELIMITER) {
                            queryEndPos += tokenLength;
                        }
                        // make script line
                        return new SQLQuery(
                            getDataSource(),
                            queryText,
                            statementStart,
                            queryEndPos - statementStart);
                    } catch (BadLocationException ex) {
                        log.warn("Can't extract query", ex); //$NON-NLS-1$
                        return null;
                    }
                }
                if (isDelimiter) {
                    statementStart = tokenOffset + tokenLength;
                }
                if (token.isEOF()) {
                    return null;
                }
                if (!hasValuableTokens && !token.isWhitespace() && !isControl) {
                    if (tokenType == SQLToken.T_COMMENT) {
                        hasValuableTokens = dialect.supportsCommentQuery();
                    } else {
                        hasValuableTokens = true;
                    }
                }
            } finally {
                if (!token.isWhitespace() && !token.isEOF()) {
                    prevNotEmptyTokenType = tokenType;
                }
            }
        }
    }

    private static int countLineFeeds(final IDocument document, final int offset, final int length) {
        int lfCount = 0;
        try {
            for (int i = offset; i < offset + length; i++) {
                if (document.getChar(i) == '\n') {
                    lfCount++;
                }
            }
        } catch (BadLocationException e) {
            log.error(e);
        }
        return lfCount;
    }

    protected List<SQLQueryParameter> parseParameters(IDocument document, int queryOffset, int queryLength) {
        final SQLDialect sqlDialect = getSQLDialect();
        boolean supportParamsInDDL = getActivePreferenceStore().getBoolean(ModelPreferences.SQL_PARAMETERS_IN_DDL_ENABLED);
        boolean execQuery = false;
        boolean ddlQuery = false;
        List<SQLQueryParameter> parameters = null;
        ruleManager.setRange(document, queryOffset, queryLength);

        boolean firstKeyword = true;
        for (; ; ) {
            IToken token = ruleManager.nextToken();
            final int tokenOffset = ruleManager.getTokenOffset();
            final int tokenLength = ruleManager.getTokenLength();
            if (token.isEOF() || tokenOffset > queryOffset + queryLength) {
                break;
            }
            // Handle only parameters which are not in SQL blocks
            int tokenType = SQLToken.T_UNKNOWN;
            if (token instanceof SQLToken) {
                tokenType = ((SQLToken) token).getType();
            }
            if (token.isWhitespace() || tokenType == SQLToken.T_COMMENT) {
                continue;
            }
            if (!supportParamsInDDL) {
                if (firstKeyword) {
                    // Detect query type
                    try {
                        String tokenText = document.get(tokenOffset, tokenLength);
                        if (ArrayUtils.containsIgnoreCase(sqlDialect.getDDLKeywords(), tokenText)) {
                            // DDL doesn't support parameters
                            ddlQuery = true;
                        } else {
                            execQuery = ArrayUtils.containsIgnoreCase(sqlDialect.getExecuteKeywords(), tokenText);
                        }
                    } catch (BadLocationException e) {
                        log.warn(e);
                    }
                    firstKeyword = false;
                }
            }
            if (tokenType == SQLToken.T_PARAMETER && tokenLength > 0) {
                try {
                    String paramName = document.get(tokenOffset, tokenLength);
                    if (ddlQuery) {
                        continue;
                    }
                    if (execQuery && paramName.equals(String.valueOf(syntaxManager.getAnonymousParameterMark()))) {
                        // Skip ? parameters for stored procedures (they have special meaning? [DB2])
                        continue;
                    }

                    if (parameters == null) {
                        parameters = new ArrayList<>();
                    }

                    SQLQueryParameter parameter = new SQLQueryParameter(
                        syntaxManager,
                        parameters.size(),
                        paramName,
                        tokenOffset - queryOffset,
                        tokenLength);

                    parameter.setPrevious(getPreviousParameter(parameters, parameter));
                    parameters.add(parameter);
                } catch (BadLocationException e) {
                    log.warn("Can't extract query parameter", e);
                }
            }
        }

        if (syntaxManager.isVariablesEnabled()) {
            try {
                // Find variables in strings, comments, etc
                // Use regex
                String query = document.get(queryOffset, queryLength);

                Matcher matcher = SQLQueryParameter.getVariablePattern().matcher(query);
                int position = 0;
                while (matcher.find(position)) {
                    {
                        int start = matcher.start();
                        int orderPos = 0;
                        SQLQueryParameter param = null;
                        if (parameters != null) {
                            for (SQLQueryParameter p : parameters) {
                                if (p.getTokenOffset() == start) {
                                    param = p;
                                    break;
                                } else if (p.getTokenOffset() < start) {
                                    orderPos++;
                                }
                            }
                        }

                        if (param == null) {
                            param = new SQLQueryParameter(syntaxManager, orderPos, matcher.group(0), start, matcher.end() - matcher.start());
                            if (parameters == null) {
                                parameters = new ArrayList<>();
                            }
                            param.setPrevious(getPreviousParameter(parameters, param));
                            parameters.add(param.getOrdinalPosition(), param);
                        }
                    }
                    position = matcher.end();
                }
            } catch (BadLocationException e) {
                log.warn("Error parsing variables", e);
            }
        }

        return parameters;
    }

    private static SQLQueryParameter getPreviousParameter(List<SQLQueryParameter> parameters, SQLQueryParameter parameter) {
        String varName = parameter.getVarName();
        if (parameter.isNamed()) {
            for (int i = parameters.size(); i > 0; i--) {
                if (parameters.get(i - 1).getVarName().equals(varName)) {
                    return parameters.get(i - 1);
                }
            }
        }
        return null;
    }

    protected List<SQLQueryParameter> parseParameters(IDocument document, SQLQuery query) {
        return parseParameters(document, query.getOffset(), query.getLength());
    }

    protected List<SQLQueryParameter> parseParameters(String query) {
        return parseParameters(new Document(query), 0, query.length());
    }

    public boolean isDisposed() {
        return
            getSourceViewer() == null ||
                getSourceViewer().getTextWidget() == null ||
                getSourceViewer().getTextWidget().isDisposed();
    }

    @Nullable
    @Override
    public ICommentsSupport getCommentsSupport() {
        final SQLDialect dialect = getSQLDialect();
        return new ICommentsSupport() {
            @Nullable
            @Override
            public Pair<String, String> getMultiLineComments() {
                return dialect.getMultiLineComments();
            }

            @Override
            public String[] getSingleLineComments() {
                return dialect.getSingleLineComments();
            }
        };
    }

    protected String[] collectContextMenuPreferencePages() {
        String[] ids = super.collectContextMenuPreferencePages();
        String[] more = new String[ids.length + 6];
        more[ids.length] = PrefPageSQLEditor.PAGE_ID;
        more[ids.length + 1] = PrefPageSQLExecute.PAGE_ID;
        more[ids.length + 2] = PrefPageSQLCompletion.PAGE_ID;
        more[ids.length + 3] = PrefPageSQLFormat.PAGE_ID;
        more[ids.length + 4] = PrefPageSQLResources.PAGE_ID;
        more[ids.length + 5] = PrefPageSQLTemplates.PAGE_ID;
        System.arraycopy(ids, 0, more, 0, ids.length);
        return more;
    }

    @Override
    public boolean visualizeError(@NotNull DBRProgressMonitor monitor, @NotNull Throwable error) {
        IDocument document = getDocument();
        SQLQuery query = new SQLQuery(getDataSource(), document.get(), 0, document.getLength());
        return scrollCursorToError(monitor, query, error);
    }

    /**
     * Error handling
     */
    protected boolean scrollCursorToError(@NotNull DBRProgressMonitor monitor, @NotNull SQLQuery query, @NotNull Throwable error) {
        try {
            DBCExecutionContext context = getExecutionContext();
            if (context == null) {
                return false;
            }
            boolean scrolled = false;
            DBPErrorAssistant errorAssistant = DBUtils.getAdapter(DBPErrorAssistant.class, context.getDataSource());
            if (errorAssistant != null) {
                DBPErrorAssistant.ErrorPosition[] positions = errorAssistant.getErrorPosition(
                    monitor, context, query.getText(), error);
                if (positions != null && positions.length > 0) {
                    int queryStartOffset = query.getOffset();
                    int queryLength = query.getLength();

                    DBPErrorAssistant.ErrorPosition pos = positions[0];
                    if (pos.line < 0) {
                        if (pos.position >= 0) {
                            // Only position
                            getSelectionProvider().setSelection(new TextSelection(queryStartOffset + pos.position, 0));
                            scrolled = true;
                        }
                    } else {
                        // Line + position
                        IDocument document = getDocument();
                        if (document != null) {
                            int startLine = document.getLineOfOffset(queryStartOffset);
                            int errorOffset = document.getLineOffset(startLine + pos.line);
                            int errorLength;
                            if (pos.position >= 0) {
                                errorOffset += pos.position;
                                errorLength = 1;
                            } else {
                                errorLength = document.getLineLength(startLine + pos.line);
                            }
                            if (errorOffset < queryStartOffset) errorOffset = queryStartOffset;
                            if (errorLength > queryLength) errorLength = queryLength;
                            if (errorOffset >= queryStartOffset + queryLength) {
                                // This may happen if error position was incorrectly detected.
                                // E.g. in SQL Server when actual error happened in some stored procedure.
                                errorOffset  = queryStartOffset + queryLength - 1;
                            }
                            getSelectionProvider().setSelection(new TextSelection(errorOffset, errorLength));
                            scrolled = true;
                        }
                    }
                }
            }
            return scrolled;
//            if (!scrolled) {
//                // Can't position on error - let's just select entire problem query
//                showStatementInEditor(result.getStatement(), true);
//            }
        } catch (Exception e) {
            log.warn("Error positioning on query error", e);
            return false;
        }
    }

    public boolean isFoldingEnabled() {
        return getActivePreferenceStore().getBoolean(SQLPreferenceConstants.FOLDING_ENABLED);
    }

    /**
     * Updates the status fields for the given category.
     *
     * @param category the category
     * @since 2.0
     */
    protected void updateStatusField(String category) {
        if (STATS_CATEGORY_SELECTION_STATE.equals(category)) {
            IStatusField field = getStatusField(category);
            if (field != null) {
                StringBuilder txt = new StringBuilder("Sel: ");
                ISelection selection = getSelectionProvider().getSelection();
                if (selection instanceof ITextSelection) {
                    ITextSelection textSelection = (ITextSelection) selection;
                    txt.append(textSelection.getLength()).append(" | ");
                    if (((ITextSelection) selection).getLength() <= 0) {
                        txt.append(0);
                    } else {
                        txt.append(textSelection.getEndLine() - textSelection.getStartLine() + 1);
                    }
                }
                field.setText(txt.toString());
            }
        } else {
            super.updateStatusField(category);
        }
    }

    /////////////////////////////////////////////////////////////////
    // Occurrences highlight

    protected void updateOccurrenceAnnotations(ITextSelection selection) {
        if (this.occurrencesFinderJob != null) {
            this.occurrencesFinderJob.cancel();
        }

        if (this.markOccurrencesUnderCursor || this.markOccurrencesForSelection) {
            if (selection != null) {
                IDocument document = this.getSourceViewer().getDocument();
                if (document != null) {
                    // Get full word
                    SQLWordDetector wordDetector = new SQLWordDetector();
                    int startPos = selection.getOffset();
                    int endPos = startPos + selection.getLength();
                    try {
                        int documentLength = document.getLength();
                        while (startPos > 0 && wordDetector.isWordPart(document.getChar(startPos - 1))) {
                            startPos--;
                        }
                        while (endPos < documentLength && wordDetector.isWordPart(document.getChar(endPos))) {
                            endPos++;
                        }
                    } catch (BadLocationException e) {
                        log.debug("Error detecting current word: " + e.getMessage());
                    }
                    String wordSelected = null;
                    String wordUnderCursor = null;
                    if (markOccurrencesUnderCursor) {
                        try {
                            wordUnderCursor = document.get(startPos, endPos - startPos).trim();
                        } catch (BadLocationException e) {
                            log.debug("Error detecting word under cursor", e);
                        }
                    }
                    if (markOccurrencesForSelection) {
                        wordSelected = selection.getText();
                        for (int i = 0; i < wordSelected.length(); i++) {
                            if (!wordDetector.isWordPart(wordSelected.charAt(i))) {
                                wordSelected = null;
                                break;
                            }
                        }
                    }

                    if (CommonUtils.isEmpty(wordSelected) || wordSelected.length() < 2) {
                        this.removeOccurrenceAnnotations();
                    } else {
                        OccurrencesFinder finder = new OccurrencesFinder(document, wordUnderCursor, wordSelected);
                        List<OccurrencePosition> positions = finder.perform();
                        if (!CommonUtils.isEmpty(positions)) {
                            this.occurrencesFinderJob = new OccurrencesFinderJob(positions);
                            this.occurrencesFinderJob.run(new NullProgressMonitor());
                        } else {
                            this.removeOccurrenceAnnotations();
                        }
                    }
                }
            }
        }
    }

    private void removeOccurrenceAnnotations() {
        IDocumentProvider documentProvider = this.getDocumentProvider();
        if (documentProvider != null) {
            IAnnotationModel annotationModel = documentProvider.getAnnotationModel(this.getEditorInput());
            if (annotationModel != null && this.occurrenceAnnotations != null) {
                synchronized (LOCK_OBJECT) {
                    this.updateAnnotationModelForRemoves(annotationModel);
                }

            }
        }
    }

    private void updateAnnotationModelForRemoves(IAnnotationModel annotationModel) {
        if (annotationModel instanceof IAnnotationModelExtension) {
            ((IAnnotationModelExtension) annotationModel).replaceAnnotations(this.occurrenceAnnotations, null);
        } else {
            int i = 0;

            for (int length = this.occurrenceAnnotations.length; i < length; ++i) {
                annotationModel.removeAnnotation(this.occurrenceAnnotations[i]);
            }
        }

        this.occurrenceAnnotations = null;
    }

    protected void installOccurrencesFinder() {
        if (this.getSelectionProvider() != null) {
            ISelection selection = this.getSelectionProvider().getSelection();
            if (selection instanceof ITextSelection) {
                this.updateOccurrenceAnnotations((ITextSelection) selection);
            }
        }

        if (this.occurrencesFinderJobCanceler == null) {
            this.occurrencesFinderJobCanceler = new SQLEditorBase.OccurrencesFinderJobCanceler();
            this.occurrencesFinderJobCanceler.install();
        }

    }

    protected void uninstallOccurrencesFinder() {
        this.markOccurrencesUnderCursor = false;
        this.markOccurrencesForSelection = false;
        if (this.occurrencesFinderJob != null) {
            this.occurrencesFinderJob.cancel();
            this.occurrencesFinderJob = null;
        }

        if (this.occurrencesFinderJobCanceler != null) {
            this.occurrencesFinderJobCanceler.uninstall();
            this.occurrencesFinderJobCanceler = null;
        }

        this.removeOccurrenceAnnotations();
    }

    public boolean isMarkingOccurrences() {
        return this.markOccurrencesUnderCursor;
    }

    public void setMarkingOccurrences(boolean markUnderCursor, boolean markSelection) {
        if (markUnderCursor != this.markOccurrencesUnderCursor || markSelection != this.markOccurrencesForSelection) {
            this.markOccurrencesUnderCursor = markUnderCursor;
            this.markOccurrencesForSelection = markSelection;
            if (this.markOccurrencesUnderCursor || this.markOccurrencesForSelection) {
                this.installOccurrencesFinder();
            } else {
                this.uninstallOccurrencesFinder();
            }
        }
    }

    private class EditorSelectionChangedListener implements ISelectionChangedListener {
        public void install(ISelectionProvider selectionProvider) {
            if (selectionProvider instanceof IPostSelectionProvider) {
                ((IPostSelectionProvider) selectionProvider).addPostSelectionChangedListener(this);
            } else if (selectionProvider != null) {
                selectionProvider.addSelectionChangedListener(this);
            }
        }

        public void uninstall(ISelectionProvider selectionProvider) {
            if (selectionProvider instanceof IPostSelectionProvider) {
                ((IPostSelectionProvider) selectionProvider).removePostSelectionChangedListener(this);
            } else if (selectionProvider != null) {
                selectionProvider.removeSelectionChangedListener(this);
            }
        }

        public void selectionChanged(SelectionChangedEvent event) {
            ISelection selection = event.getSelection();
            if (selection instanceof ITextSelection) {
                SQLEditorBase.this.updateOccurrenceAnnotations((ITextSelection) selection);
            }
        }
    }

    class OccurrencesFinderJob extends Job {
        private boolean isCanceled = false;
        private IProgressMonitor progressMonitor;
        private List<OccurrencePosition> positions;

        public OccurrencesFinderJob(List<OccurrencePosition> positions) {
            super("Occurrences Marker");
            this.positions = positions;
        }

        void doCancel() {
            this.isCanceled = true;
            this.cancel();
        }

        private boolean isCanceled() {
            return this.isCanceled || this.progressMonitor.isCanceled();
        }

        public IStatus run(IProgressMonitor progressMonitor) {
            this.progressMonitor = progressMonitor;
            if (!this.isCanceled()) {
                ITextViewer textViewer = SQLEditorBase.this.getViewer();
                if (textViewer != null) {
                    IDocument document = textViewer.getDocument();
                    if (document != null) {
                        IDocumentProvider documentProvider = SQLEditorBase.this.getDocumentProvider();
                        if (documentProvider != null) {
                            IAnnotationModel annotationModel = documentProvider.getAnnotationModel(SQLEditorBase.this.getEditorInput());
                            if (annotationModel != null) {
                                Map<Annotation, Position> annotationMap = new LinkedHashMap<>(this.positions.size());

                                for (OccurrencePosition position : this.positions) {
                                    if (this.isCanceled()) {
                                        break;
                                    }
                                    try {
                                        String message = document.get(position.offset, position.length);
                                        annotationMap.put(
                                            new Annotation(
                                                position.forSelection ?
                                                    SQLEditorContributions.OCCURRENCES_FOR_SELECTION_ANNOTATION_ID :
                                                    SQLEditorContributions.OCCURRENCES_UNDER_CURSOR_ANNOTATION_ID,
                                                false,
                                                message),
                                            position);
                                    } catch (BadLocationException ex) {
                                        //
                                    }
                                }

                                if (!this.isCanceled()) {
                                    synchronized (LOCK_OBJECT) {
                                        this.updateAnnotations(annotationModel, annotationMap);
                                    }
                                    return Status.OK_STATUS;
                                }
                            }
                        }
                    }
                }
            }
            return Status.CANCEL_STATUS;
        }

        private void updateAnnotations(IAnnotationModel annotationModel, Map<Annotation, Position> annotationMap) {
            if (annotationModel instanceof IAnnotationModelExtension) {
                ((IAnnotationModelExtension) annotationModel).replaceAnnotations(SQLEditorBase.this.occurrenceAnnotations, annotationMap);
            } else {
                SQLEditorBase.this.removeOccurrenceAnnotations();

                for (Map.Entry<Annotation, Position> mapEntry : annotationMap.entrySet()) {
                    annotationModel.addAnnotation(mapEntry.getKey(), mapEntry.getValue());
                }
            }

            SQLEditorBase.this.occurrenceAnnotations = annotationMap.keySet().toArray(new Annotation[annotationMap.keySet().size()]);
        }
    }

    class OccurrencesFinderJobCanceler implements IDocumentListener, ITextInputListener {

        public void install() {
            ISourceViewer sourceViewer = SQLEditorBase.this.getSourceViewer();
            if (sourceViewer != null) {
                StyledText text = sourceViewer.getTextWidget();
                if (text != null && !text.isDisposed()) {
                    sourceViewer.addTextInputListener(this);
                    IDocument document = sourceViewer.getDocument();
                    if (document != null) {
                        document.addDocumentListener(this);
                    }

                }
            }
        }

        public void uninstall() {
            ISourceViewer sourceViewer = SQLEditorBase.this.getSourceViewer();
            if (sourceViewer != null) {
                sourceViewer.removeTextInputListener(this);
            }

            IDocumentProvider documentProvider = SQLEditorBase.this.getDocumentProvider();
            if (documentProvider != null) {
                IDocument document = documentProvider.getDocument(SQLEditorBase.this.getEditorInput());
                if (document != null) {
                    document.removeDocumentListener(this);
                }
            }

        }

        public void documentAboutToBeChanged(DocumentEvent event) {
            if (SQLEditorBase.this.occurrencesFinderJob != null) {
                SQLEditorBase.this.occurrencesFinderJob.doCancel();
            }

        }

        public void documentChanged(DocumentEvent event) {
        }

        public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput) {
            if (oldInput != null) {
                oldInput.removeDocumentListener(this);
            }
        }

        public void inputDocumentChanged(IDocument oldInput, IDocument newInput) {
            if (newInput != null) {
                newInput.addDocumentListener(this);
            }
        }
    }

    private static class OccurrencePosition extends Position {
        boolean forSelection;

        OccurrencePosition(int offset, int length, boolean forSelection) {
            super(offset, length);
            this.forSelection = forSelection;
        }
    }

    private static class OccurrencesFinder {
        private IDocument fDocument;
        private String wordUnderCursor;
        private String wordSelected;

        OccurrencesFinder(IDocument document, String wordUnderCursor, String wordSelected) {
            this.fDocument = document;
            this.wordUnderCursor = wordUnderCursor;
            this.wordSelected = wordSelected;
        }

        public List<OccurrencePosition> perform() {
            if (CommonUtils.isEmpty(wordUnderCursor) && CommonUtils.isEmpty(wordSelected)) {
                return null;
            }

            List<OccurrencePosition> positions = new ArrayList<>();

            try {
                if (CommonUtils.equalObjects(wordUnderCursor, wordSelected)) {
                    // Search only selected words
                    findPositions(wordUnderCursor, positions, true);
                } else {
                    findPositions(wordUnderCursor, positions, false);
                    if (!CommonUtils.isEmpty(wordSelected)) {
                        findPositions(wordSelected, positions, true);
                    }
                }

            } catch (BadLocationException e) {
                log.debug("Error finding occurrences: " + e.getMessage());
            }

            return positions;
        }

        private void findPositions(String searchFor, List<OccurrencePosition> positions, boolean forSelection) throws BadLocationException {
            FindReplaceDocumentAdapter findReplaceDocumentAdapter = new FindReplaceDocumentAdapter(fDocument);
            for (int offset = 0; ; ) {
                IRegion region = findReplaceDocumentAdapter.find(offset, searchFor, true, false, !forSelection, false);
                if (region == null) {
                    break;
                }
                positions.add(
                    new OccurrencePosition(region.getOffset(), region.getLength(), forSelection)
                );
                offset = region.getOffset() + region.getLength();
            }
        }

    }

    ////////////////////////////////////////////////////////
    // Brackets

    // copied from JDT code
    public void gotoMatchingBracket() {

        ISourceViewer sourceViewer = getSourceViewer();
        IDocument document = sourceViewer.getDocument();
        if (document == null)
            return;

        IRegion selection = getSignedSelection(sourceViewer);

        IRegion region = characterPairMatcher.match(document, selection.getOffset());
        if (region == null) {
            return;
        }
        int offset = region.getOffset();
        int length = region.getLength();

        if (length < 1)
            return;

        int anchor = characterPairMatcher.getAnchor();
        // http://dev.eclipse.org/bugs/show_bug.cgi?id=34195
        int targetOffset = (ICharacterPairMatcher.RIGHT == anchor) ? offset + 1 : offset + length - 1;

        boolean visible = false;
        if (sourceViewer instanceof ITextViewerExtension5) {
            ITextViewerExtension5 extension = (ITextViewerExtension5) sourceViewer;
            visible = (extension.modelOffset2WidgetOffset(targetOffset) > -1);
        } else {
            IRegion visibleRegion = sourceViewer.getVisibleRegion();
            // http://dev.eclipse.org/bugs/show_bug.cgi?id=34195
            visible = (targetOffset >= visibleRegion.getOffset() && targetOffset <= visibleRegion.getOffset() + visibleRegion.getLength());
        }

        if (!visible) {
            return;
        }

        int adjustment = getOffsetAdjustment(document, selection.getOffset() + selection.getLength(), selection.getLength());
        targetOffset += adjustment;
        int direction = Integer.compare(selection.getLength(), 0);

        sourceViewer.setSelectedRange(targetOffset, direction);
        sourceViewer.revealRange(targetOffset, direction);
    }

    // copied from JDT code
    private static IRegion getSignedSelection(ISourceViewer sourceViewer) {
        Point viewerSelection = sourceViewer.getSelectedRange();

        StyledText text = sourceViewer.getTextWidget();
        Point selection = text.getSelectionRange();
        if (text.getCaretOffset() == selection.x) {
            viewerSelection.x = viewerSelection.x + viewerSelection.y;
            viewerSelection.y = -viewerSelection.y;
        }

        return new Region(viewerSelection.x, viewerSelection.y);
    }

    // copied from JDT code
    private static int getOffsetAdjustment(IDocument document, int offset, int length) {
        if (length == 0 || Math.abs(length) > 1)
            return 0;
        try {
            if (length < 0) {
                if (isOpeningBracket(document.getChar(offset))) {
                    return 1;
                }
            } else {
                if (isClosingBracket(document.getChar(offset - 1))) {
                    return -1;
                }
            }
        } catch (BadLocationException e) {
            //do nothing
        }
        return 0;
    }

    private static boolean isOpeningBracket(char character) {
        for (int i = 0; i < BRACKETS.length; i += 2) {
            if (character == BRACKETS[i])
                return true;
        }
        return false;
    }

    private static boolean isClosingBracket(char character) {
        for (int i = 1; i < BRACKETS.length; i += 2) {
            if (character == BRACKETS[i])
                return true;
        }
        return false;
    }

    protected class ShowPreferencesAction extends Action {
        public ShowPreferencesAction() {
            super(SQLEditorMessages.editor_sql_preference, DBeaverIcons.getImageDescriptor(UIIcon.CONFIGURATION));
        }  //$NON-NLS-1$

        public void run() {
            Shell shell = getSourceViewer().getTextWidget().getShell();
            String[] preferencePages = collectContextMenuPreferencePages();
            if (preferencePages.length > 0 && (shell == null || !shell.isDisposed()))
                PreferencesUtil.createPreferenceDialogOn(shell, preferencePages[0], preferencePages, getEditorInput()).open();
        }
    }
}
