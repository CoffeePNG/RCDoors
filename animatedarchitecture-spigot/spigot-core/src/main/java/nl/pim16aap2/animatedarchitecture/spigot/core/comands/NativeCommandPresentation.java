package nl.pim16aap2.animatedarchitecture.spigot.core.comands;

import cloud.commandframework.CommandHelpHandler;
import cloud.commandframework.CommandManager;
import cloud.commandframework.exceptions.*;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import nl.pim16aap2.animatedarchitecture.spigot.core.NativePresentation;

/** Cloud 1 parsing remains native; presentation uses RCUI and Paper's current Adventure API. */
public final class NativeCommandPresentation<C> {
  private static final int PAGE_SIZE = 8;
  private final CommandManager<C> manager;
  private final Supplier<NativePresentation> presentation;
  private final Function<C, Audience> audience;

  public NativeCommandPresentation(
      CommandManager<C> manager,
      Supplier<NativePresentation> presentation,
      Function<C, Audience> audience) {
    this.manager = manager;
    this.presentation = presentation;
    this.audience = audience;
  }

  public void install() {
    manager.registerExceptionHandler(
        InvalidSyntaxException.class,
        (sender, failure) ->
            error(
                sender,
                "invalid-syntax",
                "Correct command syntax: /" + failure.getCorrectSyntax()));
    manager.registerExceptionHandler(
        InvalidCommandSenderException.class,
        (sender, failure) ->
            error(sender, "invalid-sender", "This command cannot be used by this sender."));
    manager.registerExceptionHandler(
        NoPermissionException.class,
        (sender, failure) ->
            error(sender, "no-permission", "You do not have permission to use this command."));
    manager.registerExceptionHandler(
        ArgumentParseException.class,
        (sender, failure) ->
            error(
                sender,
                "argument-parsing",
                "Invalid argument: "
                    + java.util.Objects.toString(
                        failure.getCause().getMessage(), "Unknown argument")));
    manager.registerExceptionHandler(
        CommandExecutionException.class,
        (sender, failure) ->
            error(sender, "command-execution", "The command could not be completed."));
  }

  private void error(C sender, String key, String detail) {
    Component message =
        presentation
            .get()
            .component("cloud.exception." + key, Component.text(detail, NamedTextColor.RED));
    audience
        .apply(sender)
        .sendMessage(presentation.get().component("cloud.exception.decorated", message));
  }

  public void help(String rawQuery, C sender) {
    String query = rawQuery.trim().replaceAll("\\p{Cntrl}", " ");
    int page = 1;
    var pageSuffix = java.util.regex.Pattern.compile("^(?:(.*)\\s+)?([0-9]+)$").matcher(query);
    if (pageSuffix.matches()) {
      query = java.util.Objects.toString(pageSuffix.group(1), "").trim();
      try {
        page = Integer.parseInt(pageSuffix.group(2));
      } catch (NumberFormatException invalid) {
        page = Integer.MAX_VALUE;
      }
    }
    final String search = query.toLowerCase(Locale.ROOT);
    List<CommandHelpHandler.VerboseHelpEntry<C>> entries =
        manager.getCommandHelpHandler().getAllCommands().stream()
            .filter(entry -> !entry.getCommand().isHidden())
            .filter(
                entry ->
                    entry
                        .getCommand()
                        .getSenderType()
                        .map(type -> type.isInstance(sender))
                        .orElse(true))
            .filter(
                entry -> manager.hasPermission(sender, entry.getCommand().getCommandPermission()))
            .filter(
                entry ->
                    entry.getSyntaxString().toLowerCase(Locale.ROOT).contains(search)
                        || entry.getDescription().toLowerCase(Locale.ROOT).contains(search))
            .toList();
    var output = audience.apply(sender);
    var ui = presentation.get();
    if (entries.isEmpty()) {
      output.sendMessage(
          ui.component("cloud.help.no_results_for_query", Component.empty())
              .append(Component.text(" " + query)));
      return;
    }
    int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    if (page < 1 || page > pages) {
      output.sendMessage(
          ui.component(
              "cloud.help.page_out_of_range",
              Component.empty(),
              Placeholder.unparsed("page", Integer.toString(page)),
              Placeholder.unparsed("max_pages", Integer.toString(pages))));
      return;
    }
    output.sendMessage(ui.component("cloud.help.available_commands", Component.empty()));
    for (var entry :
        entries.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, entries.size()))) {
      Component description =
          entry.getDescription().isBlank()
              ? ui.component("cloud.help.no_description", Component.empty())
              : Component.text(entry.getDescription());
      Component row =
          Component.text("/" + entry.getSyntaxString(), NamedTextColor.GOLD)
              .clickEvent(ClickEvent.suggestCommand("/" + entry.getSyntaxString()))
              .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
              .append(description);
      output.sendMessage(ui.component("cloud.help.entry", row));
    }
    if (entries.size() == 1) {
      var command = entries.getFirst().getCommand();
      var arguments =
          command.getArguments().stream()
              .filter(
                  argument ->
                      !(argument instanceof cloud.commandframework.arguments.StaticArgument))
              .toList();
      if (!arguments.isEmpty())
        output.sendMessage(ui.component("cloud.help.arguments", Component.empty()));
      for (var argument : arguments) {
        Component row =
            Component.text(argument.getName() + ": " + command.getArgumentDescription(argument));
        if (!argument.isRequired())
          row =
              row.append(Component.text(" ("))
                  .append(ui.component("cloud.help.optional", Component.empty()))
                  .append(Component.text(")"));
        output.sendMessage(ui.component("cloud.help.argument-entry", row));
      }
    }
    String prefix = "/rcdoors help " + (query.isEmpty() ? "" : query + " ");
    Component navigation = Component.empty();
    if (page > 1)
      navigation =
          navigation.append(
              ui.component("cloud.help.click_for_previous_page", Component.empty())
                  .clickEvent(ClickEvent.runCommand(prefix + (page - 1))));
    navigation =
        navigation.append(
            ui.component("cloud.help.page-status", Component.text(" " + page + "/" + pages + " ")));
    if (page < pages)
      navigation =
          navigation.append(
              ui.component("cloud.help.click_for_next_page", Component.empty())
                  .clickEvent(ClickEvent.runCommand(prefix + (page + 1))));
    output.sendMessage(navigation);
  }
}
