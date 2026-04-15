package net.runelite.client.plugins.taskmaster;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class TaskmasterPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TaskmasterPlugin.class, TaskmasterLogPlugin.class);
		RuneLite.main(args);
	}
}
