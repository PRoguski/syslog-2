package pl.example.syslogparser;

import picocli.CommandLine;
import pl.example.syslogparser.cli.DryRunCommand;
import pl.example.syslogparser.cli.RunCommand;
import pl.example.syslogparser.cli.TestCommand;
import pl.example.syslogparser.cli.ValidateCommand;

@CommandLine.Command(
        name = "syslog-parser",
        mixinStandardHelpOptions = true,
        subcommands = {RunCommand.class, ValidateCommand.class, DryRunCommand.class, TestCommand.class},
        description = "Cisco 8000 syslog -> Kafka JSON parsing service.")
public final class App implements Runnable {

    @Override
    public void run() {
        // No subcommand given: behave like --help.
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int code = new CommandLine(new App()).execute(args);
        System.exit(code);
    }
}
