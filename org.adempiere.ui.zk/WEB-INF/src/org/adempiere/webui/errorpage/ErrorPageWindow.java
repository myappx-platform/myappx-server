package org.adempiere.webui.errorpage;

import java.util.Properties;

import org.adempiere.webui.component.FWindow;
import org.compiere.util.Env;

public class ErrorPageWindow extends FWindow
{
	/**
	 *
	 */
	private static final long serialVersionUID = -5169830531440825871L;

    protected Properties ctx;
    protected ErrorPagePanel pnlLogin;
    public ErrorPageWindow() {}

    public void init()
    {
    	this.ctx = Env.getCtx();
        initComponents();
        this.appendChild(pnlLogin);
        this.setStyle("background-color: transparent");
        setWidgetListener("onOK", "zAu.cmd0.showBusy(null)");
    }



    private void initComponents()
    {
		pnlLogin = new ErrorPagePanel(ctx, this);
	}

}
