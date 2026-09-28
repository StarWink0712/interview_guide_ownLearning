package interview.guide.common.async;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;

public class AsyncTaskCancelledException extends BusinessException {

  public AsyncTaskCancelledException(String message) {
    super(ErrorCode.NOT_FOUND, message);
  }
}
