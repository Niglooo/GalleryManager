module nigloo.gallerymanager
{
	requires java.desktop;
	requires java.net.http;
	
	requires javafx.base;
	requires javafx.controls;
	requires javafx.fxml;
	requires javafx.graphics;
	
	requires com.google.gson;
	requires methanol;
	requires org.apache.logging.log4j;
	
	requires nigloo.tools;
	
	opens nigloo.gallerymanager.model to javafx.fxml, com.google.gson, nigloo.tools;
	opens nigloo.gallerymanager.autodownloader to com.google.gson, nigloo.tools;
	opens nigloo.gallerymanager.ui to javafx.graphics, javafx.fxml, nigloo.tools;
	opens nigloo.gallerymanager.ui.dialog to javafx.graphics, javafx.fxml, nigloo.tools;
	
	opens resources.styles; // Oh shit, here we go again
}
