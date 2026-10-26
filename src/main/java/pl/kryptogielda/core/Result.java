package pl.kryptogielda.core;

/** Wynik operacji: sukces z wartością albo błąd z kodem i komunikatem. */
public final class Result<T> {
    public final boolean ok;
    public final T value;
    public final String code;
    public final String message;

    private Result(boolean ok, T value, String code, String message) {
        this.ok = ok;
        this.value = value;
        this.code = code;
        this.message = message;
    }

    public static <T> Result<T> ok(T value) { return new Result<>(true, value, null, null); }

    public static <T> Result<T> fail(String code, String message) { return new Result<>(false, null, code, message); }

    public <U> Result<U> failed() { return fail(code, message); }

    @Override
    public String toString() { return ok ? "OK(" + value + ")" : "FAIL(" + code + ": " + message + ")"; }
}
