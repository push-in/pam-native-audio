<?php

declare(strict_types=1);

// Shared harness: loads this plugin and the PAM Native core sources, and
// records the native calls the PHP facades make (no device needed).
$core = getenv('PAM_NATIVE_SRC') ?: null;
foreach ([dirname(__DIR__).'/vendor/pushinbr/pam-native/packages/native/src', dirname(__DIR__, 2).'/pam-native/packages/native/src'] as $candidate) {
    if ($core === null && is_dir($candidate)) {
        $core = $candidate;
    }
}
if ($core === null) {
    fwrite(STDERR, "Set PAM_NATIVE_SRC to pam-native/packages/native/src or run composer install.\n");
    exit(2);
}
if (is_file(dirname(__DIR__).'/vendor/autoload.php')) {
    require dirname(__DIR__).'/vendor/autoload.php';
}
$roots = [
    'Pam\\Native\\Audio\\' => dirname(__DIR__).'/src/',
    'Pam\\Native\\' => rtrim($core, '/').'/',
];
spl_autoload_register(static function (string $class) use ($roots): void {
    foreach ($roots as $prefix => $root) {
        if (str_starts_with($class, $prefix)) {
            $file = $root.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
            if (is_file($file)) {
                require $file;
            }

            return;
        }
    }
});
if (!function_exists('Pam\\Native\\signal')) {
    require rtrim($core, '/').'/functions.php';
}

final class TestDiagnostics
{
    /** @var list<string> */
    public static array $messages = [];

    /** @var array{requestId: int, module: string, method: string, payload: string}|null */
    public static ?array $moduleCall = null;

    /** @var array{requestId: int, operation: int, payload: string}|null */
    public static ?array $typedCall = null;
}

if (!function_exists('pam_native_call')) {
    function pam_native_call(int $requestId, string $module, string $method, string $payload): void
    {
        TestDiagnostics::$moduleCall = ['requestId' => $requestId, 'module' => $module, 'method' => $method, 'payload' => $payload];
    }
}
if (!function_exists('pam_native_call_typed')) {
    function pam_native_call_typed(int $requestId, int $operation, string $payload): void
    {
        TestDiagnostics::$typedCall = ['requestId' => $requestId, 'operation' => $operation, 'payload' => $payload];
    }
}
if (!function_exists('pam_native_error')) {
    function pam_native_error(string $message): void
    {
        TestDiagnostics::$messages[] = $message;
    }
}

$tests = 0;
$assert = static function (bool $condition, string $message) use (&$tests): void {
    $tests++;
    if (!$condition) {
        throw new RuntimeException($message);
    }
};
$lastCall = static function (): array {
    return TestDiagnostics::$moduleCall ?? throw new RuntimeException('No native module call was recorded.');
};
