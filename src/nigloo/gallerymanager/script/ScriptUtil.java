package nigloo.gallerymanager.script;

import lombok.extern.log4j.Log4j2;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

@Log4j2
public class ScriptUtil
{
    static {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }
    public static ScriptEngine createScriptEngine()
    {
        ScriptEngine engine = new ScriptEngineManager().getEngineByExtension("js");

        log.trace("New engine\n" +
                          "Engine name: {}\n" +
                          "Engine Version: {}\n" +
                          "Language name: {}\n" +
                          "Language version: {}",
                  engine.getFactory().getEngineName(),
                  engine.getFactory().getEngineVersion(),
                  engine.getFactory().getLanguageName(),
                  engine.getFactory().getLanguageVersion());

        engine.getContext().setAttribute("polyglot.js.allowAllAccess", true, ScriptContext.ENGINE_SCOPE);
        engine.getContext().setAttribute("util", ScriptAPIUtils.INSTANCE, ScriptContext.ENGINE_SCOPE);

        return engine;
    }
}
