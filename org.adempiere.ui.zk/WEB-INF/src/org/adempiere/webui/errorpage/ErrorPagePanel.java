package org.adempiere.webui.errorpage;

import java.util.Properties;

import javax.servlet.http.HttpServletResponse;

import org.adempiere.webui.LayoutUtils;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.ConfirmPanel;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.Window;
import org.adempiere.webui.theme.ITheme;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.adempiere.webui.window.Dialog;
import org.compiere.model.MSysConfig;
import org.zkoss.zhtml.Div;
import org.zkoss.zhtml.Table;
import org.zkoss.zhtml.Td;
import org.zkoss.zhtml.Tr;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;

public class ErrorPagePanel extends Window implements EventListener<Event>
{
	/**
	 *
	 */
	private static final long serialVersionUID = -3361823499124119753L;

	private static final String ON_LOAD_TOKEN = "onLoadToken";

    protected Properties ctx;
    protected ErrorPageWindow wndLogin;
    protected ConfirmPanel pnlButtons;

    public ErrorPagePanel(Properties ctx, ErrorPageWindow loginWindow)
    {
        this.ctx = ctx;
        this.wndLogin = loginWindow;
        init();
        this.setId("loginPanel");
        this.setSclass("login-box");

        Events.echoEvent(ON_LOAD_TOKEN, this, null);
        this.addEventListener(ON_LOAD_TOKEN, this);
    }

    private void init()
    {
		Div div = new Div();
    	div.setSclass(ITheme.LOGIN_BOX_HEADER_CLASS);
    	this.appendChild(div);

    	HttpServletResponse response = (HttpServletResponse)Executions.getCurrent().getNativeResponse();
    	int statusCode = response.getStatus();

    	Table table = new Table();
    	table.setId("grdLogin");
    	table.setDynamicProperty("cellpadding", "0");
    	table.setDynamicProperty("cellspacing", "5");
    	table.setSclass(ITheme.LOGIN_BOX_BODY_CLASS);

    	this.appendChild(table);

    	Tr tr = new Tr();
    	table.appendChild(tr);
    	tr.setStyle("height:50px");
    	Td td = new Td();
    	td.setSclass(ITheme.LOGIN_BOX_HEADER_LOGO_CLASS);
    	tr.appendChild(td);
    	td.setDynamicProperty("colspan", "2");
    	org.adempiere.webui.component.Label error = new org.adempiere.webui.component.Label("Status Code");
    	error.setStyle("font-size: 24px; color:#003894;");
        td.appendChild(error);

        tr = new Tr();
    	table.appendChild(tr);
    	tr.setStyle("height:50px");
    	td = new Td();
    	td.setSclass(ITheme.LOGIN_BOX_HEADER_LOGO_CLASS);
    	tr.appendChild(td);
    	td.setDynamicProperty("colspan", "2");
    	Label status = new Label(String.valueOf(statusCode));
    	status.setStyle("font-size: 40px; color:#003894;");
        td.appendChild(status);

       	div = new Div();
    	div.setSclass(ITheme.LOGIN_BOX_FOOTER_CLASS);
    	this.appendChild(div);

    	/** Button */
        pnlButtons = new ConfirmPanel(false, false, false, false, false, false, true);
        pnlButtons.addActionListener(this);
        Button okBtn = pnlButtons.getButton(ConfirmPanel.A_OK);
        okBtn.setWidgetListener("onClick", "zAu.cmd0.showBusy(null)");

        Button helpButton = pnlButtons.createButton(ConfirmPanel.A_HELP);
		helpButton.addEventListener(Events.ON_CLICK, this);
		helpButton.setSclass(ITheme.LOGIN_BUTTON_CLASS);
		pnlButtons.addComponentsRight(helpButton);

        LayoutUtils.addSclass(ITheme.LOGIN_BOX_FOOTER_PANEL_CLASS, pnlButtons);
        ZKUpdateUtil.setWidth(pnlButtons, null);
        pnlButtons.getButton(ConfirmPanel.A_OK).setSclass(ITheme.LOGIN_BUTTON_CLASS);
        div.appendChild(pnlButtons);
        this.appendChild(div);
	}

    public void onEvent(Event event)
    {
        if (event.getTarget().getId().equals(ConfirmPanel.A_OK))
        {
            showLogin();
        }
        else if (event.getTarget().getId().equals(ConfirmPanel.A_HELP))
        {
            openLoginHelp();
        }
        if (event.getName().equals(ON_LOAD_TOKEN))
        {
        	;
        }
        //
    }

    /**
     *  show login page
     *
    **/
    public void showLogin()
    {
    	Executions.sendRedirect(".."+ Executions.getCurrent().getContextPath());
    }

    /**
     *  show help page
     *
    **/
	private void openLoginHelp()
	{
		String helpURL = MSysConfig.getValue(MSysConfig.LOGIN_HELP_URL, "../");
		try {
			Executions.getCurrent().sendRedirect(helpURL, "_blank");
		}
		catch (Exception e) {
			String message = e.getMessage();
			Dialog.warn(0, message, "URLnotValid");
		}
	}




}
