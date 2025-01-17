package ui.structscript;

import synthesizer.Synth;
import synthesizer.sources.SignalProcessor;
import synthesizer.sources.SignalSource;
import synthesizer.sources.utils.*;
import synthesizer.sources.voices.Voice;
import ui.UtilityFileException;
import ui.UtilityFilesReader;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static synthesizer.sources.SignalSource.frequencyToVoltage;
import static ui.structscript.Interpreter.EditMode.GLOBAL;
import static ui.structscript.Interpreter.EditMode.VOICE;
import static ui.structscript.Interpreter.ValueType.TEXT;
import static ui.structscript.Interpreter.ValueType.*;
import static ui.structscript.Parser.Node;
import static ui.structscript.Parser.NodeType.*;

/**
 * output
 * vMix
 * aftertouchChannel
 *
 * lastNotePitch
 * lastNoteVelocity
 * lastNoteAftertouch
 * lastNoteReleaseVelocity
 * lastNoteGate
 * lastNoteTrigger
 *
 * v: output
 * v: pitch
 * v: velocity
 * v: aftertouch
 * v: releaseVelocity
 * v: gate
 * v: trigger
 *
 * TODO: Exponential envelope stages
 * TODO: Linear Slope limiter
 * TODO: Morph (simple and as Joranalogue's)
 * TODO: TZFM Oscillators
 * TODO: Variable Poles Filters
 * TODO: Highpass/Notch/Bandpass[/Morphable] Filters
 * TODO: (!!) Delayed SignalProcessors (???)
 * TODO: (!!!) MIDI files parsing
 * TODO: (!!!) To WAV
 * TODO: (!) lastNoteIsLegato Gate
 * TODO: (!!) Remove Dry/Wet on Effects
 * TODO: MIDI note map (for drums)
 *
 * TODO: NORMAL PARSING
 * TODO: Stereo
 */

public class Interpreter {
    private static final Map<String, Class<? extends SignalSource>> permittedClasses;
    static final Map<String, String> socketAliases;

    static {
        try {
            permittedClasses = UtilityFilesReader.getPermittedClasses();
            socketAliases = UtilityFilesReader.getSocketAliases();
        } catch (UtilityFileException e) {
            e.printStackTrace();
            System.exit(1);
            throw new RuntimeException("EXIT");
        }
    }

    public enum EditMode {GLOBAL, VOICE}

    private final Synth synth;
    private final Voice[] voices;
    private final Socket output;
    private final Socket[] voiceOutputs;
    private final Map<String, SignalSource> objects = new HashMap<>();
    private final Map<String, SignalSource[]> voiceObjects = new HashMap<>();

    enum ValueType {
        DOUBLE,
        SIGNAL,
        TEXT,
    }

    record Value(Object obj, ValueType type) {
    }

    static class TypeConversionException extends Exception {
    }

    EditMode editMode = GLOBAL;

    public Interpreter(int voicesCount) {
        voices = new Voice[voicesCount];
        this.output = new Socket();
        voiceOutputs = new Socket[voicesCount];
        SourceValue aftertouchChannel = new SourceValue("channel aftertouch");
        objects.put("aftertouchChannel", aftertouchChannel);

        SourceValue lastNotePitch = new SourceValue("last note pitch", frequencyToVoltage(440)),
                lastNoteVelocity = new SourceValue("last note velocity", 0.5),
                lastNoteAftertouch = new SourceValue("last note aftertouch"),
                lastNoteReleaseVelocity = new SourceValue("last note release velocity"),
                lastNoteGate = new SourceValue("last note gate");
        Triggerable lastNoteTrigger = new Triggerable("last note trigger");
        objects.put("lastNotePitch", lastNotePitch);
        objects.put("lastNoteVelocity", lastNoteVelocity);
        objects.put("lastNoteAftertouch", lastNoteAftertouch);
        objects.put("lastNoteReleaseVelocity", lastNoteVelocity);
        objects.put("lastNoteGate", lastNoteGate);
        objects.put("lastNoteTrigger", lastNoteTrigger);
        Voice last = new Voice(lastNotePitch, lastNoteVelocity, lastNoteAftertouch, lastNoteReleaseVelocity, lastNoteGate, lastNoteTrigger);

        voiceObjects.put("pitch", new SignalSource[voicesCount]);
        voiceObjects.put("velocity", new SignalSource[voicesCount]);
        voiceObjects.put("aftertouch", new SignalSource[voicesCount]);
        voiceObjects.put("releaseVelocity", new SignalSource[voicesCount]);
        voiceObjects.put("gate", new SignalSource[voicesCount]);
        voiceObjects.put("trigger", new SignalSource[voicesCount]);
        for (int i = 0; i < voicesCount; ++i) {
            voiceOutputs[i] = new Socket();
            SourceValue pitch = new SourceValue("voice #" + i + " pitch", frequencyToVoltage(440)),
                    velocity = new SourceValue("voice #" + i + " velocity", 0.5),
                    aftertouch = new SourceValue("voice #" + i + " aftertouch"),
                    releaseVelocity = new SourceValue("voice #" + i + " release velocity"),
                    gate = new SourceValue("voice #" + i + " gate");
            Triggerable trigger = new Triggerable("voice #" + i + " trigger");
            voiceObjects.get("pitch")[i] = pitch;
            voiceObjects.get("velocity")[i] = velocity;
            voiceObjects.get("aftertouch")[i] = aftertouch;
            voiceObjects.get("releaseVelocity")[i] = releaseVelocity;
            voiceObjects.get("gate")[i] = gate;
            voiceObjects.get("trigger")[i] = trigger;
            voices[i] = new Voice(pitch, velocity, aftertouch, releaseVelocity, gate, trigger);
        }
        synth = new Synth(voices, output, last);
        objects.put("voiceMix", new Mixer(voiceOutputs));
    }

    public Synth getSynth() {
        return synth;
    }

    private Class<?> getClass(String name) {
        return permittedClasses.get(name);
    }

    boolean globalInVoice;

    private SignalSource getObject(int voiceId, String name) throws InterpretationException {
        if (voiceId != -1) {
            if (voiceObjects.containsKey(name))
                return voiceObjects.get(name)[voiceId];
            else globalInVoice = true;
        }
        if(objects.containsKey(name))
            return objects.get(name);
        throw new InterpretationException("no such object \"" + name + "\"");
    }

    private Object convert(Value from, Class<?> toType) throws TypeConversionException {
        if (toType == String.class) {
            if (from.type == TEXT)
                return from.obj;
        }
        if (toType == double.class) {
            if (from.type == DOUBLE)
                return from.obj;
        }
        if (toType == SignalSource.class) {
            if (from.type() == DOUBLE)
                return new DC((double) from.obj);
            if (from.type() == SIGNAL)
                return from.obj;
        }
        throw new TypeConversionException();
    }

    private SignalSource instantiate(Class<?> cl, Value[] args) throws InterpretationException {
        Object[] usableArgs = new Object[args.length];
        SignalSource res = null;
        for (Constructor<?> constructor : cl.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != args.length)
                continue;
            try {
                for (int i = 0; i < args.length; ++i)
                    usableArgs[i] = convert(args[i], types[i]);
            } catch (TypeConversionException e) {
                continue;
            }
//            if (res != null)
//                throw new InterpretationException("more than one suitable constructor");
            try {
                res = (SignalSource) constructor.newInstance(usableArgs);
                break;
            } catch (InstantiationException | InvocationTargetException | IllegalAccessException e) {
                e.printStackTrace();
                // ???
                throw new InterpretationException("constructor problems");
            }
        }
        if (res == null)
            throw new InterpretationException("no such constructor");
        return res;
    }

    private SignalSource call(SignalSource obj, String func, Value... args) throws InterpretationException {
        Object[] usableArgs = new Object[args.length];
        SignalSource res = null;
        for (Method method : SignalSource.class.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals(func) || types.length != args.length)
                continue;
            try {
                for (int i = 0; i < args.length; ++i)
                    usableArgs[i] = convert(args[i], types[i]);
            } catch (TypeConversionException e) {
                continue;
            }
//            if (res != null)
//                throw new InterpretationException("more than one suitable function");
            try {
                res = (SignalSource) method.invoke(obj, usableArgs);
                break;
            } catch (InvocationTargetException | IllegalAccessException e) {
                e.printStackTrace();
                // ???
                throw new RuntimeException("function call problems");
            }
        }
        if (res == null)
            throw new InterpretationException("no such function");
        return res;
    }

    private Value eval(int voiceId, Node node) throws InterpretationException {
        if (node.type() == UNARY_MINUS) {
            Value obj = eval(voiceId, node.arg(0));
            if (obj.type == SIGNAL)
                return new Value(((SignalSource) obj.obj).attenuate(-1), obj.type);
            if (obj.type == DOUBLE)
                return new Value(-((double) obj.obj), obj.type);
            throw new InterpretationException("unary minus cannot be applied to " + obj.type.name().toLowerCase());
        }
        if(node.type() == NUMBER){
            return new Value(node.info(), DOUBLE);
        }
        if (node.type() == CONSTRUCTOR) {
            Value[] args = new Value[node.args().size()];
            for (int i = 0; i < node.args().size(); ++i)
                args[i] = eval(voiceId, node.arg(i));
            return new Value(instantiate(getClass(node.text()), args), SIGNAL);
        }
        if (node.type() == FUNCTION) {
            Value[] args = new Value[node.args().size() - 1];
            for (int i = 1; i < node.args().size(); ++i)
                args[i] = eval(voiceId, node.arg(i));
            Value obj = eval(voiceId, node.arg(0));
            if (obj.type != SIGNAL)
                throw new InterpretationException("only signals have functions");
            return new Value(call((SignalSource) obj.obj, node.text(), args), SIGNAL);
        }
        if (node.type() == OBJECT) {
            return new Value(getObject(voiceId, node.text()), SIGNAL);
        }
//        if (node.type() == SOCKET) {
//            Value signal = eval(voiceId, node.arg(0));
//            if(signal.type() != SIGNAL)
//                throw new InterpretationException("only signals have sockets");
//            return new Value(call((SignalSource) signal.obj, node.text()), SIGNAL);
//        }
        if (node.type() == ARITHMETIC_OPERATOR) {
            Value left = eval(voiceId, node.arg(0)),
                    right = eval(voiceId, node.arg(1));
            switch (node.text()) {
                case "/":
                    try {
                        double denominator = (double) convert(right, double.class);
                        if (left.type == DOUBLE)
                            return new Value(((double) left.obj) / denominator, DOUBLE);
                        if (left.type == SIGNAL)
                            return new Value(((SignalSource) left.obj).attenuate(1 / denominator), SIGNAL);
                    } catch (TypeConversionException ignore) {
                    }
                    break;
                case "*":
                    if (left.type == DOUBLE && right.type == DOUBLE)
                        return new Value(((double) left.obj) * ((double) right.obj), DOUBLE);
                    try {
                        SignalSource leftSignal = (SignalSource) convert(left, SignalSource.class),
                                rightSignal = (SignalSource) convert(right, SignalSource.class);
                        return new Value(leftSignal.attenuate(rightSignal), SIGNAL);
                    } catch (TypeConversionException ignore) {
                    }
                    break;
                case "+":
                    if (left.type == DOUBLE && right.type == DOUBLE)
                        return new Value(((double) left.obj) + ((double) right.obj), DOUBLE);
                    try {
                        SignalSource leftSignal = (SignalSource) convert(left, SignalSource.class),
                                rightSignal = (SignalSource) convert(right, SignalSource.class);
                        return new Value(leftSignal.add(rightSignal), SIGNAL);
                    } catch (TypeConversionException ignore) {
                    }
                    break;
                case "-":
                    if (left.type == DOUBLE && right.type == DOUBLE)
                        return new Value(((double) left.obj) - ((double) right.obj), DOUBLE);
                    try {
                        SignalSource leftSignal = (SignalSource) convert(left, SignalSource.class),
                                rightSignal = (SignalSource) convert(right, SignalSource.class);
                        return new Value(leftSignal.sub(rightSignal), SIGNAL);
                    } catch (TypeConversionException ignore) {
                    }
                    break;
            }
            throw new InterpretationException("cannot evaluate " + left.type.name().toLowerCase() + " " + node.text() + " " + right.type.name().toLowerCase());
        }
        throw new InterpretationException("expression expected");
    }

    private SignalSource getSignal(int voiceId, Node node) throws InterpretationException {
        Value obj = eval(voiceId, node);
        try {
            return (SignalSource) convert(obj, SignalSource.class);
        } catch (TypeConversionException e) {
            throw new InterpretationException("signal expected");
        }
    }

    private PseudoSocket getSocket(int voiceId, Node node) throws InterpretationException {
        if (node.type() == OBJECT) {
            if (node.text().equals("output"))
                return voiceId == -1 ? output : voiceOutputs[voiceId];
            SignalSource signal = getObject(voiceId, node.text());
            if (signal instanceof PseudoSocket socket)
                return socket;
            throw new InterpretationException("socket expected");
        }
        if (node.type() != SOCKET)
            throw new InterpretationException("socket expected");
        SignalSource obj = getSignal(voiceId, node.arg(0));
        String name = node.text();
        if (socketAliases.containsKey(name))
            name = socketAliases.get(name);
        try {
            return (PseudoSocket) obj.getClass().getMethod(name).invoke(obj);
        } catch (NoSuchMethodException e) {
            throw new InterpretationException("no such socket");
        } catch (InvocationTargetException | IllegalAccessException e) {
            e.printStackTrace();
            // ???
            throw new RuntimeException("socket problems");
        }
    }

    private void assign(EditMode mode, Node left, Node right) throws InterpretationException {
        if (left.type() != OBJECT)
            throw new InterpretationException("name of object on the left side of assignment required");
        String name = left.text();
        if (mode == GLOBAL) {
            objects.put(name, getSignal(-1, right));
        } else if (mode == VOICE) {
            if (!voiceObjects.containsKey(name))
                voiceObjects.put(name, new SignalSource[voices.length]);
            for (int i = 0; i < voices.length; ++i)
                voiceObjects.get(name)[i] = getSignal(i, right);
        }
        else throw new InterpretationException("assigning is not allowed in " + mode.name().toLowerCase() + " mode");
    }

    private void set(EditMode mode, Node left, Node right) throws InterpretationException {
        if (mode == GLOBAL) {
            Value rightValue = eval(-1, right);
            if (rightValue.type() != DOUBLE)
                throw new InterpretationException("number on the left side required");
            getSocket(-1, left).set((double) rightValue.obj);
        }
        else if (mode == VOICE) {
            for (int i = 0; i < voices.length; ++i) {
                Value rightValue = eval(i, right);
                if (rightValue.type() != DOUBLE)
                    throw new InterpretationException("number on the left side required");
                getSocket(i, left).set((double) rightValue.obj);
            }
        }
        else throw new InterpretationException("setting is not allowed in " + mode.name().toLowerCase() + " mode");
    }

    private void bind(EditMode mode, Node left, Node right) throws InterpretationException {
        if (mode == GLOBAL) {
            try {
                SignalSource signal = (SignalSource) convert(eval(-1, right), SignalSource.class);
                getSocket(-1, left).bind(signal);
            } catch (TypeConversionException e) {
                throw new InterpretationException("signal expected");
            }
        }
        else if (mode == VOICE) {
            for (int i = 0; i < voices.length; ++i) {
                try {
                    SignalSource signal = (SignalSource) convert(eval(i, right), SignalSource.class);
                    globalInVoice = false;
                    PseudoSocket socket = getSocket(i, left);
                    if (globalInVoice)
                        throw new InterpretationException("binding voice signal to global socket");
                    socket.bind(signal);
                } catch (TypeConversionException e) {
                    throw new InterpretationException("signal expected");
                }
            }
        }
        else throw new InterpretationException("binding is not allowed in " + mode.name().toLowerCase() + " mode");
    }

    private void modulate(EditMode mode, Node left, Node right) throws InterpretationException {
        if (mode == GLOBAL) {
            try {
                SignalSource signal = (SignalSource) convert(eval(-1, right), SignalSource.class);
                getSocket(-1, left).modulate(signal);
            } catch (TypeConversionException e) {
                throw new InterpretationException("signal expected");
            }
        }
        else if (mode == VOICE) {
            boolean globalSignal = false;
            SignalSource signal = null;
            for (int i = 0; i < voices.length; ++i) {
                try {
                    if (!globalSignal) {
                        globalInVoice = false;
                        signal = (SignalSource) convert(eval(i, right), SignalSource.class);
                        globalSignal = globalInVoice;
                    }
                    PseudoSocket socket = getSocket(i, left);
                    socket.modulate(signal);
                } catch (TypeConversionException e) {
                    throw new InterpretationException("signal expected");
                }
            }
        }
        else throw new InterpretationException("modulating is not allowed in " + mode.name().toLowerCase() + " mode");
    }

    private void process(EditMode mode, Node left, Node right) throws InterpretationException {
        if (mode == GLOBAL) {
            try {
                SignalSource signal = (SignalSource) convert(eval(-1, right), SignalSource.class);
                if (!(signal instanceof SignalProcessor processor))
                    throw new InterpretationException("processor expected");
                getSocket(-1, left).process(processor);
            } catch (TypeConversionException e) {
                throw new InterpretationException("signal expected");
            }
        }
        else if (mode == VOICE) {
            for (int i = 0; i < voices.length; ++i) {
                try {
                    globalInVoice = false;
                    SignalSource signal = (SignalSource) convert(eval(i, right), SignalSource.class);
                    if (!(signal instanceof SignalProcessor processor))
                        throw new InterpretationException("processor expected");
                    if (globalInVoice)
                        throw new InterpretationException("processing voice socket with global processor");
                    PseudoSocket socket = getSocket(i, left);
                    if (globalInVoice)
                        throw new InterpretationException("processing global socket with voice processor");
                    socket.process(processor);
                } catch (TypeConversionException e) {
                    throw new InterpretationException("signal expected");
                }
            }
        }
        else throw new InterpretationException("processing is not allowed in " + mode.name().toLowerCase() + " mode");
    }

    private void handleAST(Node ast, EditMode mode) throws InterpretationException {
        switch (ast.type()) {
            case LOAD:
                String path = ast.text();
                File file = new File("patches/" + path);
                try {
                    Scanner reader = new Scanner(file);
                    StringBuilder code = new StringBuilder();
                    while (reader.hasNextLine())
                        code.append(reader.nextLine()).append("\n");
                    List<Node> asts = null;
                    try {
                        asts = new Parser(new Lexer(code.toString()).lex()).parse();
                        interpret(asts);
                    } catch (StructScriptException e) {
                        throw new InterpretationException("error during loading \"" + path + "\":\n" + e);
                    }
                } catch (FileNotFoundException e) {
                    throw new InterpretationException("no such file found");
                }
                break;
            case MODE_CHANGE:
                if (ast.info() == "v")
                    editMode = VOICE;
                else if (ast.info() == "g")
                    editMode = GLOBAL;
                break;
            case ACTION:
                if ("=".equals(ast.text())) assign(mode, ast.arg(0), ast.arg(1));
                if (":=".equals(ast.text())) set(mode, ast.arg(0), ast.arg(1));
                if ("<-".equals(ast.text())) modulate(mode, ast.arg(0), ast.arg(1));
                if ("<=".equals(ast.text())) bind(mode, ast.arg(0), ast.arg(1));
                if ("-<-".equals(ast.text())) process(mode, ast.arg(0), ast.arg(1));
                if ("->".equals(ast.text())) modulate(mode, ast.arg(1), ast.arg(0));
                if ("=>".equals(ast.text())) bind(mode, ast.arg(1), ast.arg(0));
                if ("->-".equals(ast.text())) process(mode, ast.arg(1), ast.arg(0));
                break;
            default:
                throw new InterpretationException("action expected");
        }
    }

    private void handleAST(Node ast) throws InterpretationException {
        handleAST(ast, editMode);
    }

    public void interpret(List<Node> asts) throws InterpretationException {
        for (Node ast : asts) {
            try {
                handleAST(ast);
            } catch (InterpretationException e){
                throw new InterpretationException(ast.line(), e.getMessage());
            }
        }
    }

    public boolean run(String code) {
        try {
            List<Node> asts = new Parser(new Lexer(code).lex()).parse();
            interpret(asts);
            return true;
        } catch (StructScriptException e) {
            System.out.println(e);
            return false;
        }
    }
}
