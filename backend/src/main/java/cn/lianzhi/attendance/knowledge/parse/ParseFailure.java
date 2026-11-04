package cn.lianzhi.attendance.knowledge.parse;

final class ParseFailure extends RuntimeException {
    final ParseCode code;
    ParseFailure(ParseCode code) { super(code.name()); this.code = code; }
    static void limit(boolean exceeded) { if (exceeded) throw new ParseFailure(ParseCode.LIMIT_EXCEEDED); }
}
