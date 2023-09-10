package nigloo.gallerymanager.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.binding.StringExpression;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.TabPane.TabClosingPolicy;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import lombok.RequiredArgsConstructor;
import nigloo.gallerymanager.autodownloader.Downloader;
import nigloo.gallerymanager.autodownloader.Downloader.FilesConfiguration;
import nigloo.gallerymanager.autodownloader.Downloader.FilesConfiguration.AutoExtractZip;
import nigloo.gallerymanager.autodownloader.Downloader.FilesConfiguration.DownloadFiles;
import nigloo.gallerymanager.autodownloader.Downloader.ImagesConfiguration;
import nigloo.gallerymanager.autodownloader.Downloader.ImagesConfiguration.DownloadImages;
import nigloo.gallerymanager.autodownloader.DownloaderType;
import nigloo.gallerymanager.model.Artist;
import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Tag;
import nigloo.gallerymanager.ui.dialog.NewDownloaderDialog;
import nigloo.gallerymanager.ui.dialog.NewDownloaderDialog.NewDownloaderInfo;
import nigloo.gallerymanager.ui.util.UIUtils;
import nigloo.tool.Utils;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;
import nigloo.tool.injection.annotation.PostConstruct;
import nigloo.tool.javafx.component.EditableIntegerSpinner;
import nigloo.tool.javafx.component.dialog.AlertWithIcon;
import nigloo.tool.javafx.component.dialog.ExceptionDialog;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ArtistsEditor extends SplitPane {

    @Inject
    private Gallery gallery;

    @FXML
    private ListView<ArtistData> artists;
    @FXML
    private Node artistEditor;
    @FXML
    private TextField artistName;
    @FXML
    private TextField artistTag;
    @FXML
    private TabPane downloaders;



    private record ArtistData(
            Artist artist,
            SimpleStringProperty name,
            SimpleStringProperty tag,
            BooleanBinding changed,
            List<Tab> downloaderEditorTabs)
    { }

    public ArtistsEditor() {
        UIController.loadFXML(this, "artists_editor.fxml");
        if (!Injector.enabled())
            return;
        Injector.init(this);

        artists.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ArtistData item, boolean empty) {
                super.updateItem(item, empty);
                textProperty().unbind();
                if (empty || item == null) {
                    setText(null);
                } else {
                    textProperty().bind(
                            Bindings.createStringBinding(() -> item.changed.get() ? "* " : "", item.changed)
                                    .concat(item.name));
                }
            }
        });
        artists.getSelectionModel().selectedItemProperty().addListener(this::setCurrentArtist);

        downloaders.setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
    }

    @PostConstruct
    private void postConstruct()
    {
        UIUtils.addableTabs(downloaders, "Add Downloader", () ->
                new NewDownloaderDialog()
                        .showAndWait()
                        .map(info -> makeDownloaderEditorTab(gallery.newDownloader(
                                artists.getSelectionModel().getSelectedItem().artist(),
                                info.type(),
                                info.creatorId())))
                        .orElse(null));

        for (Artist artist : gallery.getArtists())
        {
            SimpleStringProperty name = new SimpleStringProperty(artist.getName());
            SimpleStringProperty tag = new SimpleStringProperty(artist.getTag().getName());

            List<Tab> downloaderTabs = new ArrayList<>();
            for (Downloader downloader : artist.getAutodownloaders())
            {
                Tab tab = makeDownloaderEditorTab(downloader);
                downloaderTabs.add(tab);
            }

            artists.getItems().add(new ArtistData(
                    artist,
                    name,
                    tag,
                    new BooleanBinding()
                    {
                        {
                            bind(name, tag);
                        }

                        @Override
                        protected boolean computeValue()
                        {
                            return !name.get().equals(artist.getName())
                                    || !Tag.normalize(tag.get()).equals(artist.getTag().getName());
                        }
                    },
                    downloaderTabs));
        }

        artistEditor.setDisable(true);
    }

    private Tab makeDownloaderEditorTab(Downloader downloader)
    {
        DownloaderEditor downloaderEditor = new DownloaderEditor(downloader);
        Tab tab = new Tab("", downloaderEditor);
        tab.textProperty().bind(Bindings.createStringBinding(() -> downloaderEditor.changed.get() ? "* " : "", downloaderEditor.changed).concat(downloaderEditor.title));

        tab.setOnCloseRequest(e -> {
            AlertWithIcon warningPopup = new AlertWithIcon(AlertType.WARNING);
            warningPopup.setTitle("Delete downloader");
            warningPopup.setHeaderText("Delete downloader \"" + downloaderEditor.title.get() + "\"?");
            warningPopup.setContentText("This action cannot be undone!");
            warningPopup.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
            warningPopup.setDefaultButton(ButtonType.NO);

            Optional<ButtonType> button = warningPopup.showAndWait();
            if (button.isEmpty() || button.get() != ButtonType.YES)
            {
                e.consume();
                return;
            }

            gallery.deleteDownloader(downloader);
        });

        return tab;
    }

    private void setCurrentArtist(ObservableValue<? extends ArtistData> obs, ArtistData oldArtist, ArtistData newArtist)
    {
        if (oldArtist != null)
        {
            oldArtist.name.unbind();
            oldArtist.tag.unbind();
        }

        downloaders.getTabs().subList(0, downloaders.getTabs().size() - 1).clear();

        if (newArtist != null)
        {
            artistEditor.setDisable(false);
            artistName.setText(newArtist.name.get());
            newArtist.name.bind(artistName.textProperty());
            artistTag.setText(newArtist.tag.get());
            newArtist.tag.bind(artistTag.textProperty());
            downloaders.getTabs().addAll(0, newArtist.downloaderEditorTabs);
            downloaders.getSelectionModel().selectFirst();
        }
        else
        {
            artistEditor.setDisable(true);
            artistName.setText("");
            artistTag.setText("");
        }
    }

    @FXML
    public void saveArtist()
    {
        ArtistData artistData = artists.getSelectionModel().getSelectedItem();
        if (artistData == null)
            return;

        try
        {
            validate(artistData);
        }
        catch (IllegalArgumentException e)
        {
            new ExceptionDialog(e, "Validation error").show();
            return;
        }

        Artist artist = artistData.artist;

        artist.setName(artistData.name.get());
        artist.setTag(gallery.getTag(artistData.tag.get()));

        for(Tab tab : artistData.downloaderEditorTabs)
        {
            DownloaderEditor editor = (DownloaderEditor) tab.getContent();
            Downloader downloader = editor.downloader;

            downloader.setCreatorId(editor.creatorId.getText());
            
            ImagesConfiguration ic = new ImagesConfiguration();
            ic.setDownload(editor.imageDownload.getValue());
            ic.setPathPattern(editor.imagePathPattern.getText());
            downloader.setImageConfiguration(ic);
            
            FilesConfiguration fc = new FilesConfiguration();
            fc.setDownload(editor.fileDownload.getValue());
            fc.setPathPattern(editor.filePathPattern.getText());
            fc.setAutoExtractZip(editor.fileAutoExtractZip.getValue());
            downloader.setFileConfiguration(fc);

            LocalDate localDate = editor.mostRecentPostCheckedDate.getValue();
            downloader.setMostRecentPostCheckedDate(localDate != null ? localDate.atStartOfDay(ZoneId.systemDefault()) : null);
            downloader.setMinDelayBetweenRequests(editor.minDelayBetweenRequests.getValue());
            String titleFilter = editor.titleFilterRegex.getText();
            downloader.setTitleFilterRegex(Utils.isNotBlank(titleFilter) ? Pattern.compile(titleFilter) : null);
        }
    }

    private void validate(ArtistData artistData) throws IllegalArgumentException
    {
        if (Utils.isBlank(artistData.name.get()))
            throw new IllegalArgumentException("Artist name cannot be empty");
        if (Utils.isBlank(artistData.tag.get()))
            throw new IllegalArgumentException("Artist tag cannot be empty");

        for(Tab tab : artistData.downloaderEditorTabs) {
            DownloaderEditor editor = (DownloaderEditor) tab.getContent();
            if (Utils.isBlank(editor.creatorId.getText()))
                throw new IllegalArgumentException("Downloader creator ID cannot be empty");
            if (editor.imageDownload.getValue() != DownloadImages.NO &&
                    Utils.isBlank(editor.imagePathPattern.getText()))
                throw new IllegalArgumentException("Downloader image path pattern cannot be empty");
            if (editor.fileDownload.getValue() != DownloadFiles.NO &&
                    Utils.isBlank(editor.filePathPattern.getText()))
                throw new IllegalArgumentException("Downloader file path pattern cannot be empty");
            if (Utils.isNotBlank(editor.titleFilterRegex.getText())) {
                try
                {
                    Pattern.compile(editor.titleFilterRegex.getText());
                }
                catch (PatternSyntaxException e)
                {
                    throw new IllegalArgumentException("Downloader title filter pattern invalid", e);
                }
            }
        }
    }

    @FXML
    public void reloadArtist()
    {
        ArtistData artistData = artists.getSelectionModel().getSelectedItem();
        if (artistData == null)
            return;

        Artist artist = artistData.artist;

        artistName.setText(artist.getName());
        artistTag.setText(artist.getTag().getName());

        artistData.downloaderEditorTabs
                .stream()
                .map(Tab::getContent)
                .map(DownloaderEditor.class::cast)
                .forEach(DownloaderEditor::reload);
    }

    @FXML
    public void deleteArtist()
    {
        //TODO deleteArtist
    }

    @FXML
    public void newArtist() //create temp artist and actualy add on saveArtist
    {
        //TODO newArtist
    }

    @FXML
    public void newDownloader()//(popup to select type) create temp dowloader and actualy add on saveArtist
    {
        //TODO newDownloader
    }

    @FXML
    public void removeDownloader() // mark as toremove and actualy remove on saveArtist
    {
        //TODO removeDownloader
    }

    private class DownloaderEditor extends VBox
    {
        private final Downloader downloader;

        @FXML
        private Label type;
        @FXML
        private TextField creatorId;
        @FXML
        private TitledPane imagesConfigurationPane;
        @FXML
        private ComboBox<DownloadImages> imageDownload;
        @FXML
        private TextField imagePathPattern;
        @FXML
        private TitledPane filesConfigurationPane;
        @FXML
        private ComboBox<DownloadFiles> fileDownload;
        @FXML
        private TextField filePathPattern;
        @FXML
        private ComboBox<AutoExtractZip> fileAutoExtractZip;
        @FXML
        private DatePicker mostRecentPostCheckedDate;
        @FXML
        private EditableIntegerSpinner minDelayBetweenRequests;
        @FXML
        private TextField titleFilterRegex;

        private final BooleanBinding changed;
        private final StringExpression title;

        public DownloaderEditor(Downloader downloader)
        {
            this.downloader = downloader;
            UIController.loadFXML(this, "downloader_editor.fxml");

            type.setText(downloader.getType().toString());
            imageDownload.getItems().addAll(DownloadImages.values());
            fileDownload.getItems().addAll(DownloadFiles.values());
            fileAutoExtractZip.getItems().addAll(AutoExtractZip.values());
            minDelayBetweenRequests.setMin(0);

            reload();

            if (imageDownload.getValue() == DownloadImages.NO)
                imagesConfigurationPane.setExpanded(false);
            if (fileDownload.getValue() == DownloadFiles.NO)
                filesConfigurationPane.setExpanded(false);

            changed = new BooleanBinding()
            {
                {
                    bind(creatorId.textProperty(),
                         imageDownload.getSelectionModel().selectedItemProperty(),
                         imagePathPattern.textProperty(),
                         fileDownload.getSelectionModel().selectedItemProperty(),
                         filePathPattern.textProperty(),
                         fileAutoExtractZip.getSelectionModel().selectedItemProperty(),
                         minDelayBetweenRequests.valueProperty(),
                         titleFilterRegex.textProperty());
                }

                @Override
                protected boolean computeValue()
                {
                    if(!creatorId.getText().equals(downloader.getCreatorId()))
                        return true;

                    ImagesConfiguration imgConf = downloader.getImageConfiguration();
                    DownloadImages di = imgConf.getDownload();
                    DownloadImages dis = imageDownload.getValue();
                    if ((di == null || di == DownloadImages.NO) && (dis == null || dis == DownloadImages.NO))
                    {
                        // No image download: don't validate other image download fields
                    }
                    else
                    {
                        if (di != dis)
                            return true;

                        if (!(Utils.isBlank(imagePathPattern.getText()) && Utils.isBlank(imgConf.getPathPattern())) &&
                                !Objects.equals(imagePathPattern.getText(), imgConf.getPathPattern()))
                            return true;
                    }

                    FilesConfiguration fileConf = downloader.getFileConfiguration();
                    DownloadFiles df = fileConf.getDownload();
                    DownloadFiles dfs = fileDownload.getValue();
                    if ((df == null || df == DownloadFiles.NO) && (dfs == null || dfs == DownloadFiles.NO))
                    {
                        // No file download: don't validate other file download fields
                    }
                    else
                    {
                        if (df != dfs)
                            return true;

                        if (!(Utils.isBlank(filePathPattern.getText()) && Utils.isBlank(fileConf.getPathPattern())) &&
                                !Objects.equals(filePathPattern.getText(), fileConf.getPathPattern()))
                            return true;

                        AutoExtractZip aez = fileConf.getAutoExtractZip();
                        AutoExtractZip aezs = fileAutoExtractZip.getValue();
                        if ((aez == null || aez == AutoExtractZip.NO) && (aezs == null || aezs == AutoExtractZip.NO))
                            return true;
                        if (aez != aezs)
                            return true;
                    }

                    LocalDate localDate = downloader.getMostRecentPostCheckedDate() != null ? downloader.getMostRecentPostCheckedDate().toLocalDate() : null;
                    if (!Objects.equals(mostRecentPostCheckedDate.getValue(), localDate))
                        return true;

                    if(minDelayBetweenRequests.getValue() != downloader.getMinDelayBetweenRequests())
                        return true;

                    String pattern = downloader.getTitleFilterRegex() != null ? downloader.getTitleFilterRegex().pattern() : null;
                    if (!(Utils.isBlank(titleFilterRegex.getText()) && Utils.isBlank(pattern)) &&
                            !Objects.equals(titleFilterRegex.getText(), pattern))
                        return true;

                    return false;
                }
            };

            title = type.textProperty()
                        .concat(" (")
                        .concat(creatorId.textProperty())
                        .concat(")");
        }

        public void reload()
        {
            creatorId.setText(downloader.getCreatorId());

            DownloadImages di = downloader.getImageConfiguration().getDownload();
            imageDownload.getSelectionModel().select(di != null ? di : DownloadImages.NO);
            imagePathPattern.setText(downloader.getImageConfiguration().getPathPattern());

            DownloadFiles df = downloader.getFileConfiguration().getDownload();
            fileDownload.getSelectionModel().select(df != null ? df : DownloadFiles.NO);
            filePathPattern.setText(downloader.getFileConfiguration().getPathPattern());
            AutoExtractZip aez = downloader.getFileConfiguration().getAutoExtractZip();
            fileAutoExtractZip.getSelectionModel().select(aez != null ? aez : AutoExtractZip.NO);

            mostRecentPostCheckedDate.setValue(downloader.getMostRecentPostCheckedDate() != null ? downloader.getMostRecentPostCheckedDate().toLocalDate() : null);
            minDelayBetweenRequests.setValue((int) downloader.getMinDelayBetweenRequests());
            titleFilterRegex.setText(downloader.getTitleFilterRegex() != null ? downloader.getTitleFilterRegex().pattern() : null);

        }
    }
}
