# Supermemory + Room hybrid for Memory

Supermemory cloud gives semantic recall over Preferences and Interactions, but requires network and an API key; Room alone is offline and simple but keyword-only. We use Supermemory behind a MemoryRepository interface with Room as offline mirror and source for view/delete UI, so commands work offline and cloud recall enhances when online.
