package dev.n2k02.mcplogger.shizuku;

// No arbitrary shell strings or Binder transactions accepted from clients.
// Operations are mapped to fixed read-only commands in the privileged service.
interface IReadOnlyDiagnostics {
    String read(int operation) = 1;
    int remoteUid() = 2;
    void destroy() = 16777114;
}
