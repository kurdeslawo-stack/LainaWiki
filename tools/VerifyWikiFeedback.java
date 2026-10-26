import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;

/** Read-only check of the deployed plugin on Paper; no players, inventories or progress are changed. */
public final class VerifyWikiFeedback {
    public static void main(String[] args) throws Exception {
        var vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1]);}finally{vm.detach();}
    }
    public static void agentmain(String ignored, Instrumentation instrumentation) throws Exception {
        Class<?> bukkit=Arrays.stream(instrumentation.getAllLoadedClasses()).filter(type->type.getName().equals("org.bukkit.Bukkit")).findFirst().orElseThrow();
        Object server=bukkit.getMethod("getServer").invoke(null);
        Object manager=server.getClass().getMethod("getPluginManager").invoke(server);
        Object plugin=manager.getClass().getMethod("getPlugin",String.class).invoke(manager,"LainaWiki");
        if(plugin==null)throw new IllegalStateException("LainaWiki absent");
        var loader=plugin.getClass().getClassLoader();
        Object scheduler=server.getClass().getMethod("getScheduler").invoke(server);
        var pluginType=loader.loadClass("org.bukkit.plugin.Plugin");
        var schedulerType=loader.loadClass("org.bukkit.scheduler.BukkitScheduler");
        schedulerType.getMethod("runTask",pluginType,Runnable.class).invoke(scheduler,plugin,(Runnable)()->verify(plugin,loader));
    }
    private static void verify(Object plugin,ClassLoader loader) {
        Logger logger=null;
        try {
            logger=(Logger)plugin.getClass().getMethod("getLogger").invoke(plugin);
            check((boolean)plugin.getClass().getMethod("isEnabled").invoke(plugin),"Plugin disabled");
            Object index=field(plugin,"index"),catalog=field(plugin,"catalog");
            var titles=(List<?>)index.getClass().getMethod("titles").invoke(index);
            check(titles.contains("Ezoteryzm")&&titles.contains("Cash Shop"),"General articles absent");
            var other=(List<?>)index.getClass().getMethod("otherArticles",catalog.getClass()).invoke(index,catalog);
            check(other.contains("Ezoteryzm"),"Mechanic classified as item");
            Object command=plugin.getClass().getMethod("getCommand",String.class).invoke(plugin,"wiki");
            Object executor=command.getClass().getMethod("getExecutor").invoke(command);
            var completer=loader.loadClass("org.bukkit.command.TabCompleter");
            var suggestions=(List<?>)completer.getMethod("onTabComplete",loader.loadClass("org.bukkit.command.CommandSender"),
                    loader.loadClass("org.bukkit.command.Command"),String.class,String[].class)
                    .invoke(executor,null,command,"wiki",new String[]{"Ezot"});
            check(suggestions.contains("Ezoteryzm"),"General article tab completion absent");
            Class<?> service=loader.loadClass("pl.laina.reforge.wiki.WikiArticleService");
            Method parser=service.getDeclaredMethod("parseDialogText",String.class,String.class);parser.setAccessible(true);
            Object rich=parser.invoke(null,"<b>mocny</b> <u>tekst</u> <i>kursywa</i>&lt;br/&gt;dalej","https://wiki.laina.pl/");
            var formats=(List<?>)rich.getClass().getMethod("formats").invoke(rich);
            String text=(String)rich.getClass().getMethod("text").invoke(rich);
            check(formats.size()==3&&text.contains("\ndalej")&&!text.contains("<"),"Formatting or newline failed");
            logger.info("WIKI_FEEDBACK_RUNTIME_PASS articles="+titles.size()+" otherArticles="+other.size()
                    +" autocomplete=Ezoteryzm styles=3 encodedBreak=true");
        }catch(Throwable failure){if(logger!=null)logger.log(java.util.logging.Level.SEVERE,"WIKI_FEEDBACK_RUNTIME_FAILED",failure);else throw new RuntimeException(failure);}
    }
    private static Object field(Object instance,String name)throws Exception {var field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
