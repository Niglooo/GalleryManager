package nigloo.gallerymanager.ui.util;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;

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
}
