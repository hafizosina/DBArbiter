package com.dbadmin.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;

/** A data grid with the value panel beside it. The panel's visibility is shared by all views. */
class DataView extends BorderPane {

    /** Show/hide the value panel everywhere at once. */
    private static final BooleanProperty SHOW_VALUE_PANEL = new SimpleBooleanProperty(true);

    private final DataGrid grid = new DataGrid();
    private final CellInspector inspector = new CellInspector();
    private final SplitPane split = new SplitPane(grid);
    private double divider = 0.7;

    DataView() {
        grid.selectedCellProperty().addListener((o, a, cell) -> inspector.show(cell));
        SplitPane.setResizableWithParent(inspector, false);
        setCenter(split);
        SHOW_VALUE_PANEL.addListener((o, a, show) -> applyVisibility(show));
        applyVisibility(SHOW_VALUE_PANEL.get());
    }

    DataGrid grid() {
        return grid;
    }

    private void applyVisibility(boolean show) {
        if (show && !split.getItems().contains(inspector)) {
            split.getItems().add(inspector);
            split.setDividerPositions(divider);
        } else if (!show && split.getItems().contains(inspector)) {
            divider = split.getDividerPositions()[0];
            split.getItems().remove(inspector);
        }
    }

    /** A toolbar toggle for the value panel. */
    static ToggleButton valuePanelToggle() {
        ToggleButton toggle = new ToggleButton("Value Panel");
        toggle.setTooltip(new Tooltip("Show the selected cell's full value, formatted"));
        toggle.selectedProperty().bindBidirectional(SHOW_VALUE_PANEL);
        return toggle;
    }
}
