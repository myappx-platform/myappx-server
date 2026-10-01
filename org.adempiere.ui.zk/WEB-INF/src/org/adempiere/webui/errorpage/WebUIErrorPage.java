package org.adempiere.webui.errorpage;

import org.zkoss.zul.Window;

public class WebUIErrorPage extends Window
{
	/**
	 *
	 */
	private static final long serialVersionUID = -3320656546509525766L;


    public WebUIErrorPage()
    {
    	this.setVisible(false);
    }

    public void onCreate()
    {
        ErrorPage loginDesktop = new ErrorPage();
    	loginDesktop.createPart(this.getPage());
    }

}
