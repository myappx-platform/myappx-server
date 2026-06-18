package org.adempiere.webui.errorpage;

import org.adempiere.webui.part.AbstractUIPart;
import org.adempiere.webui.theme.ThemeManager;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.metainfo.PageDefinition;
import org.zkoss.zul.Borderlayout;

public class ErrorPage extends AbstractUIPart
{
	private Borderlayout layout;

    public ErrorPage()
    {
    	;
    }

    protected Component doCreatePart(Component parent)
    {
    	PageDefinition pageDefintion = Executions.getCurrent().getPageDefinition(ThemeManager.getThemeResource("zul/error/error.zul"));
    	Component loginPage = Executions.createComponents(pageDefintion, parent, null);

        layout = (Borderlayout) loginPage.getFellow("layout");

        ErrorPageWindow loginWindow = (ErrorPageWindow) loginPage.getFellow("loginWindow");
        loginWindow.init();

        return layout;
    }


	public Component getComponent() {
		return layout;
	}

}
