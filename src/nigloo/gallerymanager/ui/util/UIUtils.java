package nigloo.gallerymanager.ui.util;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.util.function.Supplier;

public class UIUtils {
    private UIUtils() {
        throw new UnsupportedOperationException();
    }

    public static void addableTabs(TabPane tabPane, String newTabButtonText, Supplier<Tab> newTab) {

        Button addTabButton = new Button(newTabButtonText);
        addTabButton.setOnAction(e -> tryAddPane(tabPane, newTab));

        StackPane addTabContent = new StackPane(addTabButton);
        addTabContent.setAlignment(Pos.CENTER);

        Tab addTab = new Tab(" + ", addTabContent);
        addTab.setClosable(false);
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, previousTab, selectedTab) -> {
            if (selectedTab == addTab && tabPane.getTabs().size() > 1)
            {
                if (!tryAddPane(tabPane, newTab)) {
                    tabPane.getSelectionModel().select(previousTab);
                }
            }
        });

        tabPane.getTabs().add(addTab);
    }

    private static boolean tryAddPane(TabPane tabPane, Supplier<Tab> newTab) {
        Tab tab = newTab.get();
        if (tab == null)
            return false;

        tabPane.getTabs().add(tabPane.getTabs().size()-1, tab);
        tabPane.getSelectionModel().select(tab);
        return true;
    }

    public static void debugBorder(Region region, Color color) {
        if (region == null)
            return;
        if (color == null)
            color = Color.RED;

        BorderStroke bs = new BorderStroke(color, BorderStrokeStyle.SOLID, new CornerRadii(0), new BorderWidths(1d));
        region.setBorder(new Border(bs, bs, bs, bs));
    }
}
