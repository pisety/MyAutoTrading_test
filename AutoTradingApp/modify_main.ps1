import re

with open('app/src/main/java/com/my/autotradingapp/MainActivity.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Remove LaunchedEffect that clears watch_symbols
pattern_launched_effect = r'\s*var previousIsOverseas by remember \{ mutableStateOf\(isOverseas\) \}\s*LaunchedEffect\(isOverseas\) \{\s*if \(previousIsOverseas != isOverseas\) \{\s*viewModel\.updateWatchSymbols\(emptyList\(\), "stock"\)\s*previousIsOverseas = isOverseas\s*\}\s*\}'
content = re.sub(pattern_launched_effect, '', content)

# Filter watchSymbols for management dialog and list
# In StockTab:
# val watchSymbolsList = settings.watch_symbols.keys.toList()
# Should be:
# val filteredWatchSymbols = settings.watch_symbols.filterKeys { sym -> if (isOverseas) !sym.all { it.isDigit() } else sym.all { it.isDigit() } }
# val watchSymbolsList = filteredWatchSymbols.keys.toList()

# We need to change both initialSymbols inside showWatchlistDialog and watchSymbolsList below it.
old_dialog = '''WatchlistManagementDialog(
            initialSymbols = settings.watch_symbols.entries.sortedBy { it.value.order }.map { (code, _) ->'''
new_dialog = '''val filteredWatchSymbols = settings.watch_symbols.filterKeys { sym -> if (isOverseas) !sym.all { it.isDigit() } else sym.all { it.isDigit() } }
        WatchlistManagementDialog(
            initialSymbols = filteredWatchSymbols.entries.sortedBy { it.value.order }.map { (code, _) ->'''
content = content.replace(old_dialog, new_dialog)

old_save = '''viewModel.updateWatchSymbols(managedList, "stock")'''
new_save = '''viewModel.updateWatchSymbols(managedList, "stock", isOverseas)'''
content = content.replace(old_save, new_save)

old_list = '''val watchSymbolsList = settings.watch_symbols.keys.toList()'''
new_list = '''val watchSymbolsList = filteredWatchSymbols.keys.toList()'''
content = content.replace(old_list, new_list)

with open('app/src/main/java/com/my/autotradingapp/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated MainActivity.kt")
