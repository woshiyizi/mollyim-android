package org.thoughtcrime.securesms.conversationlist;

import android.app.Application;
import android.database.ContentObserver;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.paging.DataSource;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;

import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.conversationlist.model.Conversation;
import org.thoughtcrime.securesms.conversationlist.model.SearchResult;
import org.thoughtcrime.securesms.database.DatabaseContentProviders;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.megaphone.Megaphone;
import org.thoughtcrime.securesms.megaphone.MegaphoneRepository;
import org.thoughtcrime.securesms.megaphone.Megaphones;
import org.thoughtcrime.securesms.search.SearchRepository;
import org.thoughtcrime.securesms.util.Debouncer;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil;
import org.thoughtcrime.securesms.util.paging.Invalidator;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

class ConversationListViewModel extends ViewModel {

  private static final String TAG = Log.tag(ConversationListViewModel.class);

  private final Application                       application;
  private final MutableLiveData<Megaphone>        megaphone;
  private final MutableLiveData<SearchResult>     searchResult;
  private final LiveData<ConversationList>        conversationList;
  private final SearchRepository                  searchRepository;
  private final MegaphoneRepository               megaphoneRepository;
  private final Debouncer                         debouncer;
  private final ContentObserver                   observer;
  private final Invalidator                       invalidator;

  private String lastQuery;

  private ConversationListViewModel(@NonNull Application application, @NonNull SearchRepository searchRepository, Repository repository) {
    this.application         = application;
    this.megaphone           = new MutableLiveData<>();
    this.searchResult        = new MutableLiveData<>();
    this.searchRepository    = searchRepository;
    this.megaphoneRepository = ApplicationDependencies.getMegaphoneRepository();
    this.debouncer           = new Debouncer(300);
    this.invalidator         = new Invalidator();
    this.observer            = new ContentObserver(new Handler()) {
      @Override
      public void onChange(boolean selfChange) {
        if (!TextUtils.isEmpty(getLastQuery())) {
          searchRepository.query(getLastQuery(), searchResult::postValue);
        }
      }
    };

    DataSource.Factory<Integer, Conversation> factory = repository.getDataSourceFactory(invalidator);
    PagedList.Config                          config  = new PagedList.Config.Builder()
                                                                            .setPageSize(15)
                                                                            .setInitialLoadSizeHint(30)
                                                                            .setEnablePlaceholders(true)
                                                                            .build();

    LiveData<PagedList<Conversation>> conversationList = new LivePagedListBuilder<>(factory, config).setFetchExecutor(ConversationListDataSource.EXECUTOR)
                                                                                                    .setInitialLoadKey(0)
                                                                                                    .build();

    application.getContentResolver().registerContentObserver(DatabaseContentProviders.ConversationList.CONTENT_URI, true, observer);

    this.conversationList = Transformations.switchMap(conversationList, conversation -> {
      if (conversation.getDataSource().isInvalid()) {
        Log.w(TAG, "Received an invalid conversation list. Ignoring.");
        return new MutableLiveData<>();
      }

      MutableLiveData<ConversationList> updated = new MutableLiveData<>();

      if (repository instanceof NoteListRepository) {
        SignalExecutors.BOUNDED.execute(() -> {
          List<MaterialColor> palette = DatabaseFactory.getThreadDatabase(application).getNoteColorList();
          updated.postValue(new ConversationList(conversation, 0, 0, palette));
        });
      } else if (repository.isArchived()) {
        updated.postValue(new ConversationList(conversation, 0, 0, Collections.emptyList()));
      } else {
        SignalExecutors.BOUNDED.execute(() -> {
          int archiveCount = DatabaseFactory.getThreadDatabase(application).getArchivedConversationListCount();
          int pinnedCount  = DatabaseFactory.getThreadDatabase(application).getPinnedConversationListCount();
          updated.postValue(new ConversationList(conversation, archiveCount, pinnedCount, Collections.emptyList()));
        });
      }

      return updated;
    });
  }

  public LiveData<Boolean> hasNoConversations() {
    return Transformations.map(getConversationList(), ConversationList::isEmpty);
  }

  @NonNull LiveData<SearchResult> getSearchResult() {
    return searchResult;
  }

  @NonNull LiveData<Megaphone> getMegaphone() {
    return megaphone;
  }

  @NonNull LiveData<ConversationList> getConversationList() {
    return conversationList;
  }

  public int getPinnedCount() {
    return Objects.requireNonNull(getConversationList().getValue()).pinnedCount;
  }

  void onVisible() {
    megaphoneRepository.getNextMegaphone(megaphone::postValue);
  }

  void onMegaphoneCompleted(@NonNull Megaphones.Event event) {
    megaphone.postValue(null);
    megaphoneRepository.markFinished(event);
  }

  void onMegaphoneSnoozed(@NonNull Megaphones.Event event) {
    megaphoneRepository.markSeen(event);
    megaphone.postValue(null);
  }

  void onMegaphoneVisible(@NonNull Megaphone visible) {
    megaphoneRepository.markVisible(visible.getEvent());
  }

  void updateQuery(String query) {
    lastQuery = query;
    debouncer.publish(() -> searchRepository.query(query, result -> {
      Util.runOnMain(() -> {
        if (query.equals(lastQuery)) {
          searchResult.setValue(result);
        }
      });
    }));
  }

  private @NonNull String getLastQuery() {
    return lastQuery == null ? "" : lastQuery;
  }

  @Override
  protected void onCleared() {
    invalidator.invalidate();
    debouncer.clear();
    application.getContentResolver().unregisterContentObserver(observer);
  }

  interface Repository {
    DataSource.Factory<Integer, Conversation> getDataSourceFactory(@NonNull Invalidator invalidator);
    boolean isArchived();
  }

  static final class Factory implements ViewModelProvider.Factory {

    private final Repository repository;

    public Factory(Repository repository) {
      this.repository = repository;
    }

    @SuppressWarnings("unchecked")
    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return (T) new ConversationListViewModel(ApplicationDependencies.getApplication(), new SearchRepository(), repository);
    }
  }

  final static class ConversationList {
    private final PagedList<Conversation> conversations;
    private final int                     archivedCount;
    private final int                     pinnedCount;
    private final List<MaterialColor>     palette;

    ConversationList(PagedList<Conversation> conversations, int archivedCount, int pinnedCount, List<MaterialColor> palette) {
      this.conversations = conversations;
      this.archivedCount = archivedCount;
      this.pinnedCount   = pinnedCount;
      this.palette       = palette;
    }

    PagedList<Conversation> getConversations() {
      return conversations;
    }

    int getArchivedCount() {
      return archivedCount;
    }

    public int getPinnedCount() {
      return pinnedCount;
    }

    boolean isEmpty() {
      return conversations.isEmpty() && archivedCount == 0;
    }

    List<MaterialColor> getPalette() {
      return palette;
    }
  }
}
