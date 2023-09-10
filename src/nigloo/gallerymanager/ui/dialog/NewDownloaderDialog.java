package nigloo.gallerymanager.ui.dialog;

import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;
import nigloo.gallerymanager.autodownloader.DownloaderType;
import nigloo.gallerymanager.ui.UIController;
import nigloo.gallerymanager.ui.dialog.NewDownloaderDialog.NewDownloaderInfo;
import nigloo.tool.Utils;

public class NewDownloaderDialog extends Dialog<NewDownloaderInfo>
{
    public record NewDownloaderInfo(DownloaderType type, String creatorId) {}

    @FXML
    private ComboBox<DownloaderType> type;
    @FXML
    private TextField creatorId;

    public NewDownloaderDialog()
    {
        UIController.loadFXML(this, getDialogPane(),"new_downloader_popup.fxml");

        type.getItems().addAll(DownloaderType.values());
        type.setValue(DownloaderType.values()[0]);

        Node okButton = getDialogPane().lookupButton(ButtonType.OK);
        creatorId.textProperty().addListener((obs, oldValue, newValue) -> okButton.setDisable(Utils.isBlank(newValue)));

        okButton.setDisable(true);

        setResultConverter(buttonType -> (buttonType == ButtonType.OK) ? new NewDownloaderInfo(type.getValue(), creatorId.getText()) : null);
    }
}
