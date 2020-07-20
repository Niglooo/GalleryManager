module nigloo.gallerymanager {
	requires javafx.base;
	requires javafx.controls;
	requires javafx.fxml;
	requires javafx.graphics;
	
	requires com.google.gson;
	
	requires nigloo.tools;
	
	opens nigloo.gallerymanager.model				to com.google.gson;
	opens nigloo.gallerymanager.ui					to javafx.graphics, javafx.fxml;
	opens resources.fxml;
}