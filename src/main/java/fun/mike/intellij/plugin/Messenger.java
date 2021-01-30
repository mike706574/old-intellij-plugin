package fun.mike.intellij.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

public class Messenger {
    private final Project project;

    public Messenger(Project project) {
        this.project = project;
    }

    public void show(String message) {
        Messages.showMessageDialog(project, message, "Message", Messages.getInformationIcon());
    }
}