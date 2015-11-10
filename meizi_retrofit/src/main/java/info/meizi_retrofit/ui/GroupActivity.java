package info.meizi_retrofit.ui;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.app.SharedElementCallback;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.ViewTreeObserver;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import butterknife.Bind;
import butterknife.ButterKnife;
import info.meizi_retrofit.R;
import info.meizi_retrofit.adapter.GroupAdapter;
import info.meizi_retrofit.base.BaseActivity;
import info.meizi_retrofit.model.Content;
import info.meizi_retrofit.net.ContentApi;
import info.meizi_retrofit.net.ContentParser;
import info.meizi_retrofit.utils.LogUtils;
import info.meizi_retrofit.utils.StringConverter;
import info.meizi_retrofit.utils.Utils;
import io.realm.Realm;
import retrofit.RestAdapter;
import retrofit.client.OkClient;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 * Created by Mr_Wrong on 15/10/31.
 */
public class GroupActivity extends BaseActivity implements SwipeRefreshLayout.OnRefreshListener {
    public static final String INDEX = "index";
    public static final String GROUPID = "groupid";
    public static final String COLOR = "color";
    @Bind(R.id.group_recyclerview)
    RecyclerView mRecyclerview;
    @Bind(R.id.group_refresher)
    SwipeRefreshLayout mRefresher;
    @Bind(R.id.group_toolbar)
    Toolbar mToolbar;
    private String groupid;
    public int color;
    private GroupAdapter mAdapter;
    private ContentApi mApi;
    private List<Content> lists = new CopyOnWriteArrayList<>();//多线程并发写
    private StaggeredGridLayoutManager layoutManager;
    private final OkHttpClient client = new OkHttpClient();
    private Realm realm;
    private Bundle reenterState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.group_layout);
        ButterKnife.bind(this);

        mRefresher.setOnRefreshListener(this);
        realm = Realm.getInstance(this);
        setSupportActionBar(mToolbar);
        mToolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp);
        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                supportFinishAfterTransition();
            }
        });

        mApi = createApi();
        groupid = getIntent().getStringExtra(GROUPID);
        color = getIntent().getIntExtra(COLOR, getResources().getColor(R.color.app_primary_color));

        Utils.setSystemBar(this,mToolbar,color);

        mRefresher.setColorSchemeColors(color);

        mAdapter = new GroupAdapter(this) {
            @Override
            protected void onItemClick(View v, int position) {
                startLargePicActivity(v, position);
            }
        };
        layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerview.setLayoutManager(layoutManager);
        mRecyclerview.setAdapter(mAdapter);
        sendToLoad();


        setExitSharedElementCallback(new SharedElementCallback() {
            @Override
            public void onMapSharedElements(List<String> names, Map<String, View> sharedElements) {
                if (reenterState != null) {
                    int i = reenterState.getInt(INDEX, 0);
                    sharedElements.clear();
                    sharedElements.put(mAdapter.get(i).getUrl(), layoutManager.findViewByPosition(i));
                    reenterState = null;
                }
            }
        });

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        realm.close();
    }

    private void sendToLoad() {
        Utils.statrtRefresh(mRefresher, true);
        if (!Content.all(realm, groupid).isEmpty()) {//数据库有 直接加载
            List<Content> list = Content.all(realm, groupid);
            mAdapter.replaceWith(list);
            Utils.statrtRefresh(mRefresher, false);
            LogUtils.d("获取本地资源");
        } else {
            LogUtils.d("加载网络资源");
            loadData();
        }
    }

    private void loadData() {
        //先去获取count  然后根据count去查询全部的content
        mSubscriptions.add(mApi.getContentCount(groupid)
                .flatMap(new Func1<String, Observable<Integer>>() {
                    @Override
                    public Observable<Integer> call(String s) {
                        return Observable.just(ContentParser.getCount(s));
                    }
                })
                .flatMap(new Func1<Integer, Observable<Integer>>() {
                    @Override
                    public Observable<Integer> call(Integer integer) {
                        return Observable.range(1, integer);
                    }
                })
                .flatMap(new Func1<Integer, Observable<String>>() {
                    @Override
                    public Observable<String> call(Integer integer) {
                        return mApi.getContent(groupid, integer);
                    }
                })
                .map(new Func1<String, Content>() {
                    @Override
                    public Content call(String s) {
                        Content content = null;//这里没有使用接口，是因为一定要setEndpoint 所以作罢。。
                        try {
                            content = handleContent(ContentParser.ParserContent(s));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        return content;
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(new Action1<Content>() {
                    @Override
                    public void call(Content content) {
                        saveDB(content);
                    }
                })
                .subscribe(new Subscriber<Content>() {
                    @Override
                    public void onCompleted() {
                        mAdapter.replaceWith(Content.all(realm, groupid));
                        mRefresher.setRefreshing(false);
                    }

                    @Override
                    public void onError(Throwable e) {
                        LogUtils.d(e);
                    }

                    @Override
                    public void onNext(Content content) {
                        lists.add(content);
                    }
                }));

    }

    //从网络获取的图片也应该先存入数据库，方便排序
    //不过这样获取要等待整个的list都获取完成才能显示  耗时太久  如果单个返回 排序也是个问题
    //会造成图片的移动排序  效果也不好  暂时先整个list吧
    private void saveDB(Content content) {
        realm.beginTransaction();
        realm.copyToRealmOrUpdate(content);
        realm.commitTransaction();
        LogUtils.d("存入数据库");
    }

    int mI = 0;

    private Content handleContent(Content content) throws IOException {
        Response response = client.newCall(new Request.Builder().url(content.getUrl()).build()).execute();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(response.body().byteStream(), null, options);
        content.setImagewidth(options.outWidth);
        content.setImageheight(options.outHeight);
        content.setGroupid(groupid);
        content.setOrder(Integer.parseInt(groupid + mI++));
        return content;
    }

    @Override
    public void onActivityReenter(int resultCode, Intent data) {
        super.onActivityReenter(resultCode, data);
        supportStartPostponedEnterTransition();
        reenterState = new Bundle(data.getExtras());
        mRecyclerview.scrollToPosition(reenterState.getInt(INDEX, 0));
        mRecyclerview.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mRecyclerview.getViewTreeObserver().removeOnPreDrawListener(this);
                mRecyclerview.requestLayout();
                return true;
            }
        });
    }

    private ContentApi createApi() {
        RestAdapter adapter = new RestAdapter.Builder()
                .setEndpoint(ContentApi.BASE_URL)
                .setConverter(new StringConverter())
                .setClient(new OkClient())
                .build();
        return adapter.create(ContentApi.class);
    }

    private void startLargePicActivity(View view, int position) {
        Intent intent = new Intent(this, LargePicActivity.class);
        intent.putExtra(INDEX, position);
        intent.putExtra(GROUPID, groupid);
        ActivityOptionsCompat options = ActivityOptionsCompat
                .makeSceneTransitionAnimation(this, view, mAdapter.get(position).getUrl());
        startActivity(intent, options.toBundle());
    }

    @Override
    public void onRefresh() {
        sendToLoad();
    }

}
