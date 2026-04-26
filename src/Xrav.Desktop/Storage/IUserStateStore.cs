using Xrav.Core.State;

namespace Xrav.Desktop.Storage;

public interface IUserStateStore
{
    UserState Load();
    void Save(UserState state);
}
