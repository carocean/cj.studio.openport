package cj.studio.openport;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import cj.studio.ecm.CJSystem;
import cj.studio.ecm.IServiceSite;
import cj.studio.ecm.ServiceCollection;
import cj.studio.ecm.annotation.CjService;
import cj.studio.ecm.net.Circuit;
import cj.studio.ecm.net.CircuitException;
import cj.studio.ecm.net.Frame;
import cj.studio.openport.annotations.CjOpenport;
import cj.ultimate.util.StringUtil;

public class DefaultOpenportServiceContainer implements IOpenportServiceContainer, IOpenportPrinter {
	IAccessControlStrategy acsStrategy;
	ICheckTokenStrategy ctstrategy;
	Map<String, SecurityCommand> commands;// key为地址：/myservice.service#method1则直接访问到方法,/myservice#method1,因此服务名的索引直接以此作key
	public DefaultOpenportServiceContainer(IServiceSite site, IAccessControlStrategy acsstrategy,
										   ICheckTokenStrategy ctstrategy) {
		commands = new HashMap<>();
		this.acsStrategy = acsstrategy;
		this.ctstrategy = ctstrategy;
		site.addService("$.security.container", this);
		ServiceCollection<IOpenportService> col = site.getServices(IOpenportService.class);
		for (IOpenportService ss : col) {
			CjService cjService = ss.getClass().getAnnotation(CjService.class);
			String sname = cjService.name();
			Class<?>[] faces = ss.getClass().getInterfaces();
			for (Class<?> c : faces) {
				if (!IOpenportService.class.isAssignableFrom(c)) {
					continue;
				}
				CjOpenport perm = c.getAnnotation(CjOpenport.class);
				if (perm == null) {
					CJSystem.logging().warn(getClass(), String.format("缺少注解@CjOpenport，在接口：%s", c.getName()));
					continue;
				}
				CJSystem.logging().info(String.format("发现安全服务：%s，类型：%s", sname, ss.getClass().getName()));

				fillCommand(sname, c, ss);
			}
		}
	}

	private void fillCommand(String servicepath, Class<?> face, IOpenportService ss) {
		Method[] methods = face.getMethods();
		for (Method m : methods) {
			CjOpenport cjPermission = m.getAnnotation(CjOpenport.class);
			if (cjPermission == null) {
				continue;
			}
			
			String key = String.format("%s#%s", servicepath, m.getName());
			CJSystem.logging().info(String.format("\t\t服务命令：%s", m.getName()));
			SecurityCommand cmd = new SecurityCommand(servicepath, face, ss, m,this.acsStrategy,this.ctstrategy);
			if (acsStrategy.isInvisible(cmd.acl)) {
				CJSystem.logging().warn(String.format("\t\t\t %s 由于对所有人不可见因此被忽略", m.getName()));
				cmd.dispose();
				continue;
			}
			commands.put(key, cmd);
		}
	}

	@Override
	public void dispose() {
		commands.clear();
	}

	@Override
	public boolean matchesAPI(Frame frame) {
		return frame.relativePath().startsWith("/cjstudio/openport/");
	}

	@Override
	public boolean matchesAndSelectKey(Frame frame) throws CircuitException {
		// 根据相对路径要到服务实例，再根据command查找服务实现中的方法。
		// 地址：/myservice.service#method1则直接访问到方法,/myservice#method1,因此服务名的索引直接以此作key
		String command = frame.head("Rest-Command");
		if (StringUtil.isEmpty(command)) {
			command = frame.parameter("Rest-Command");
		}
		if (StringUtil.isEmpty(command)) {
			command = "index";
		}
		String relpath = frame.relativePath();

		String key = String.format("%s#%s", relpath, command);
		if (this.commands.containsKey(key)) {
			frame.head("Security-SelectedKey", key);
			return true;
		}
		String newrelpath = "";
		if (relpath.endsWith("/")) {
			newrelpath = relpath.substring(0, relpath.length() - 1);
		} else {
			newrelpath = newrelpath + "/";
		}
		key = String.format("%s#%s", newrelpath, command);
		if (this.commands.containsKey(key)) {
			frame.head("Security-SelectedKey", key);
			return true;
		}
		return false;
	}

	@Override
	public void invokeService(Frame frame, Circuit circuit) throws CircuitException {
		if (!frame.containsHead("Security-SelectedKey")) {
			throw new CircuitException("501", "未选择key");
		}
		String key = frame.head("Security-SelectedKey");
		if (StringUtil.isEmpty(key)) {
			return;
		}
		SecurityCommand scmd = this.commands.get(key);
		if (scmd == null) {
			return;
		}
		scmd.doCommand(frame, circuit);
	}

	@Override
	public void printPort(OpenportContext context) {

	}
}