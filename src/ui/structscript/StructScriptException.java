package ui.structscript;

class StructScriptException extends Exception {
    private final int line;
    public StructScriptException(int line) {
        this.line = line;
    }

    public StructScriptException(int line, String message) {
        super(message);
        this.line = line;
    }

    public int getLine(){
        return line;
    }

    @Override
    public String toString() {
        return "StructScript Exception: " + getMessage();
    }
}
