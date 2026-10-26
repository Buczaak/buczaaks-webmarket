package pl.kryptogielda.core;

import java.util.HashMap;
import java.util.Map;

/** English texts (language: en). Same keys as {@link Messages#DEFAULTS}. */
final class MessagesEn {
    private MessagesEn() {}

    static final Map<String, String> MAP = new HashMap<>();

    private static void d(String k, String v) { MAP.put(k, v); }

    static {
        d("chat.trade.buy", "&8[&aMarket&8] &aPlayer &f{player} &aBUYS &f{qty} pcs. &e{coin} &afor &a{value} {cur}!");
        d("chat.trade.sell", "&8[&cMarket&8] &cPlayer &f{player} &cSELLS &f{qty} pcs. &e{coin} &afor &c{value} {cur}!");
        d("chat.limit.buy", "&8[&eLimit Order&8] &aPlayer &f{player} &aautomatically BUYS &f{qty} pcs. &e{coin} &afor &a{value} {cur}!");
        d("chat.limit.sell", "&8[&eLimit Order&8] &cPlayer &f{player} &cautomatically SELLS &f{qty} pcs. &e{coin} &afor &c{value} {cur}!");
        d("chat.bot.buy", "&8[&eMarket-Bot&8] &a{player} PUMPS &e{coin} &a(Value: {value} {cur})!");
        d("chat.bot.sell", "&8[&eMarket-Bot&8] &c{player} DUMPS &e{coin} &c(Value: {value} {cur})!");
        d("update.title", "&8[&6Market&8] &e&lCrypto Market Update!");
        d("update.trend", "&8 » &7Server-wide trend: {trend}");
        d("update.coin", "&8 » &f{coin}: &6{price} {cur} {change}");
        d("trend.flat", "&7Stable ➔");
        d("trend.up", "&aIncreased Liquidity (Bull ↗)");
        d("trend.down", "&cLiquidity Crisis (Bear ↘)");
        d("change.up", "&a(+{diff} {cur})");
        d("change.down", "&c({diff} {cur})");
        d("change.flat", "&7(0 {cur})");
        d("event.line", "&8[&6Market - {type}&8] {color}{text}");
        d("reset.broadcast", "&8[&c&lMARKET&8] &4&lATTENTION! &cThe crypto market has been reset! A new era begins!");
        d("prefix", "&8[&6Market&8] ");
        d("err.no-permission", "&cYou don't have permission to use this command.");
        d("err.player-only", "&cThis command is for players only.");
        d("err.no-economy", "&cThe server economy (Vault) is unavailable. Try again later.");
        d("err.busy", "&cThe market is busy for a moment, please try again.");
        d("err.internal", "&cA market error occurred. Please notify an administrator.");
        d("err.invalid-number", "&cEnter a valid number (e.g. 100 or 12.5).");
        d("ok.bought", "&aBought &e{qty}x {coin} &afor &c{value} {cur}&a.");
        d("ok.sold", "&aSold &e{qty}x {coin} &afor &a+{value} {cur}&a.");
        d("err.not-enough-money", "&cNot enough funds! You need &e{value} {cur}&c.");
        d("err.cant-afford-one", "&cYou can't afford even a single piece!");
        d("err.not-enough-assets", "&cYou don't have enough assets in your portfolio!");
        d("deposit.ok", "&aDeposited &e{value} {cur} &ato your market account. Balance: &e{balance} {cur}&a.");
        d("deposit.not-enough", "&cYou don't have that much money on the server!");
        d("deposit.usage", "&7Usage: &f/deposit <amount>");
        d("withdraw.ok", "&aWithdrew &e{value} {cur} &ato the game.");
        d("withdraw.usage", "&7Usage: &f/withdraw <amount>");
        d("withdraw.failed", "&cWithdrawal failed - the funds returned to your market account.");
        d("link.code", "&aWebsite login code: &e&l{code} &7(valid for {minutes} min.)");
        d("link.url", "&aMarket website: &b{url}");
        d("link.disabled", "&cThe website is disabled in the configuration.");
        d("balance.line", "&7Market account: &e{balance} {cur}&7, total net worth: &e{worth} {cur}");
        d("err.price-moved", "&cThe price changed during the transaction - try again. Your funds were returned.");
        d("err.generic", "&c{message}");
        d("err.unknown-coin", "&cNo such cryptocurrency.");
        d("err.no-account", "&cYou don't have a market account yet.");
        d("err.deposit-failed", "&cWithdrawing from your server funds failed.");
        d("ok.sold-refunded", "&cCould not pay out to the game - {value} {cur} went to your market account.");
        d("ok.refund-diff", "&7Refunded the difference: &e{value} {cur}&7.");
        d("withdraw.queued", "&aWithdrawal of &e{value} {cur} &aqueued - the funds will arrive shortly.");
        d("help.header", "&8[&6Market&8] &eCommands:");
        d("help.menu", "&f/market &7- opens the market menu");
        d("help.link", "&f/market link &7- website login code");
        d("help.wallet", "&f/market portfolio &7- your cryptocurrencies");
        d("help.market", "&f/market rates &7- current prices");
        d("help.balance", "&f/market balance &7- market account balance");
        d("help.deposit", "&f/deposit <amount> &7- move money from the game to your market account");
        d("help.withdraw", "&f/withdraw <amount> &7- withdraw from your market account to the game");
        d("gui.main.portfolio", "&b&lYour portfolio");
        d("gui.main.portfolio.lore", "&7The cryptocurrencies you own.");
        d("gui.main.market", "&e&lMarket");
        d("gui.main.market.lore", "&7Current prices and trading.");
        d("gui.main.web", "&a&lWebsite");
        d("gui.main.web.lore", "&7Charts and limit orders: &f/market link");
        d("gui.coin.name", "&e&l{coin}");
        d("gui.coin.click", "&e[Click to open the trading panel]");
        d("gui.coin.holding", "&7Owned volume: &b{qty} pcs.");
        d("gui.coin.value", "&7Current value: &6{value} {cur}");
        d("gui.coin.cost", "&7Total invested: &c{cost} {cur}");
        d("gui.coin.pl", "&7Unrealised result: {color}{diff} {cur}");
        d("gui.coin.trend", "&7Current trend: &f{trend}");
        d("gui.coin.buy-price", "&7Buy price: &c{price} {cur}");
        d("gui.coin.sell-price", "&7Sell price: &a{price} {cur}");
        d("gui.portfolio.empty", "&7You don't own any cryptocurrencies yet.");
        d("gui.broker.buy", "&a&lBUY {amount}");
        d("gui.broker.buy-max", "&a&lBUY MAX");
        d("gui.broker.sell", "&c&lSELL {amount}");
        d("gui.broker.sell-all", "&c&lSELL ALL");
        d("gui.broker.wallet", "&6Your funds: &e{balance} {cur}");
        d("gui.broker.holding", "&bYou own: &f{qty} pcs. &7(value {value} {cur})");
        d("gui.broker.fee", "&7Selling fee: &f{fee}%");
        d("admin.ok", "&aDone.");
        d("admin.usage", "&7Usage: &f{usage}");
        d("gui.main.title", "&8» &6Crypto Market &8«");
        d("gui.portfolio.title", "&8» &bYour Crypto Portfolio &8«");
        d("gui.market.title", "&8» &eCrypto Market (Live) &8«");
        d("gui.broker.title", "&8» &6Broker: {coin} &8«");
        d("gui.back", "&cBack");
        d("gui.back.main", "&7Return to the main market menu.");
        d("gui.back.market", "&cBack to the Market");
    }
}
